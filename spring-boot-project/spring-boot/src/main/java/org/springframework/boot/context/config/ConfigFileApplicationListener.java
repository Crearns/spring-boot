/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link EnvironmentPostProcessor} that configures the context environment by loading
 * properties from well known file locations. By default properties will be loaded from
 * 'application.properties' and/or 'application.yml' files in the following locations:
 * <ul>
 * <li>file:./config/</li>
 * <li>file:./</li>
 * <li>classpath:config/</li>
 * <li>classpath:</li>
 * </ul>
 * The list is ordered by precedence (properties defined in locations higher in the list
 * override those defined in lower locations).
 * <p>
 * Alternative search locations and names can be specified using
 * {@link #setSearchLocations(String)} and {@link #setSearchNames(String)}.
 * <p>
 * Additional files will also be loaded based on active profiles. For example if a 'web'
 * profile is active 'application-web.properties' and 'application-web.yml' will be
 * considered.
 * <p>
 * The 'spring.config.name' property can be used to specify an alternative name to load
 * and the 'spring.config.location' property can be used to specify alternative search
 * locations or specific files.
 * <p>
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Eddú Meléndez
 * @author Madhura Bhave
 */
public class ConfigFileApplicationListener
		implements EnvironmentPostProcessor, SmartApplicationListener, Ordered {

	private static final String DEFAULT_PROPERTIES = "defaultProperties";

	// Note the order is from least to most specific (last one wins)
	private static final String DEFAULT_SEARCH_LOCATIONS = "classpath:/,classpath:/config/,file:./,file:./config/";

	private static final String DEFAULT_NAMES = "application";

	private static final Set<String> NO_SEARCH_NAMES = Collections.singleton(null);

	private static final Bindable<String[]> STRING_ARRAY = Bindable.of(String[].class);

	/**
	 * The "active profiles" property name.
	 */
	public static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";

	/**
	 * The "includes profiles" property name.
	 */
	public static final String INCLUDE_PROFILES_PROPERTY = "spring.profiles.include";

	/**
	 * The "config name" property name.
	 */
	public static final String CONFIG_NAME_PROPERTY = "spring.config.name";

	/**
	 * The "config location" property name.
	 */
	public static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";

	/**
	 * The "config additional location" property name.
	 */
	public static final String CONFIG_ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	/**
	 * The default order for the processor.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final DeferredLog logger = new DeferredLog();

	private String searchLocations;

	private String names;

	private int order = DEFAULT_ORDER;

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType)
				|| ApplicationPreparedEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		// <1> 如果是 ApplicationEnvironmentPreparedEvent 事件，说明 Spring 环境准备好了，则执行相应的处理
		if (event instanceof ApplicationEnvironmentPreparedEvent) {
			onApplicationEnvironmentPreparedEvent(
					(ApplicationEnvironmentPreparedEvent) event);
		}

		// <2> 如果是 ApplicationPreparedEvent 事件，说明 Spring 容器初始化好了，则进行相应的处理。
		if (event instanceof ApplicationPreparedEvent) {
			onApplicationPreparedEvent(event);
		}
	}

	private void onApplicationEnvironmentPreparedEvent(
			ApplicationEnvironmentPreparedEvent event) {
		// <1.1> 加载指定类型 EnvironmentPostProcessor 对应的，在 `META-INF/spring.factories` 里的类名的数组
		List<EnvironmentPostProcessor> postProcessors = loadPostProcessors();
		// 加入自己
		postProcessors.add(this);
		// 排序 postProcessors 数组
		AnnotationAwareOrderComparator.sort(postProcessors);
		// 遍历 postProcessors 数组，逐个执行。
		for (EnvironmentPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessEnvironment(event.getEnvironment(),
					event.getSpringApplication());
		}
	}

	List<EnvironmentPostProcessor> loadPostProcessors() {
		return SpringFactoriesLoader.loadFactories(EnvironmentPostProcessor.class,
				getClass().getClassLoader());
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment,
			SpringApplication application) {
		addPropertySources(environment, application.getResourceLoader());
	}

	private void onApplicationPreparedEvent(ApplicationEvent event) {
		// 修改 logger 文件，暂时可以无视。
		this.logger.switchTo(ConfigFileApplicationListener.class);
		// 添加 PropertySourceOrderingPostProcessor 处理器
		addPostProcessors(((ApplicationPreparedEvent) event).getApplicationContext());
	}

	/**
	 * Add config file property sources to the specified environment.
	 * @param environment the environment to add source to
	 * @param resourceLoader the resource loader
	 * @see #addPostProcessors(ConfigurableApplicationContext)
	 */
	protected void addPropertySources(ConfigurableEnvironment environment,
			ResourceLoader resourceLoader) {
		// <1> 添加 RandomValuePropertySource 到 environment 中
		RandomValuePropertySource.addToEnvironment(environment);
		// <2> 创建 Loader 对象，进行加载
		new Loader(environment, resourceLoader).load();
	}

	/**
	 * Add appropriate post-processors to post-configure the property-sources.
	 * @param context the context to configure
	 */
	protected void addPostProcessors(ConfigurableApplicationContext context) {
		context.addBeanFactoryPostProcessor(
				new PropertySourceOrderingPostProcessor(context));
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the search locations that will be considered as a comma-separated list. Each
	 * search location should be a directory path (ending in "/") and it will be prefixed
	 * by the file names constructed from {@link #setSearchNames(String) search names} and
	 * profiles (if any) plus file extensions supported by the properties loaders.
	 * Locations are considered in the order specified, with later items taking precedence
	 * (like a map merge).
	 * @param locations the search locations
	 */
	public void setSearchLocations(String locations) {
		Assert.hasLength(locations, "Locations must not be empty");
		this.searchLocations = locations;
	}

	/**
	 * Sets the names of the files that should be loaded (excluding file extension) as a
	 * comma-separated list.
	 * @param names the names to load
	 */
	public void setSearchNames(String names) {
		Assert.hasLength(names, "Names must not be empty");
		this.names = names;
	}

	/**
	 * {@link BeanFactoryPostProcessor} to re-order our property sources below any
	 * {@code @PropertySource} items added by the {@link ConfigurationClassPostProcessor}.
	 */
	private class PropertySourceOrderingPostProcessor
			implements BeanFactoryPostProcessor, Ordered {

		private ConfigurableApplicationContext context;

		PropertySourceOrderingPostProcessor(ConfigurableApplicationContext context) {
			this.context = context;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
				throws BeansException {
			reorderSources(this.context.getEnvironment());
		}

		private void reorderSources(ConfigurableEnvironment environment) {
			// 移除 DEFAULT_PROPERTIES 的属性源
			PropertySource<?> defaultProperties = environment.getPropertySources()
					.remove(DEFAULT_PROPERTIES);
			// 添加到 environment 尾部
			if (defaultProperties != null) {
				environment.getPropertySources().addLast(defaultProperties);
			}
		}

	}

	/**
	 * Loads candidate property sources and configures the active profiles.
	 */
	private class Loader {

		private final Log logger = ConfigFileApplicationListener.this.logger;

		private final ConfigurableEnvironment environment;

		/**
		 * 占位符解析器
		 */
		private final PropertySourcesPlaceholdersResolver placeholdersResolver;

		/**
		 * 资源加载器 参考IoC代码
		 */
		private final ResourceLoader resourceLoader;

		/**
		 * yaml/yml  properties 的资源加载器
		 */
		private final List<PropertySourceLoader> propertySourceLoaders;

		private Deque<Profile> profiles;

		private List<Profile> processedProfiles;

		private boolean activatedProfiles;

		private Map<Profile, MutablePropertySources> loaded;

		private Map<DocumentsCacheKey, List<Document>> loadDocumentsCache = new HashMap<>();

		Loader(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
			this.environment = environment;
			// <1> 创建 PropertySourcesPlaceholdersResolver 对象
			this.placeholdersResolver = new PropertySourcesPlaceholdersResolver(
					this.environment);
			// <2> 创建 DefaultResourceLoader 对象
			this.resourceLoader = (resourceLoader != null) ? resourceLoader
					: new DefaultResourceLoader();
			// <3> 加载指定类型 PropertySourceLoader 对应的，在 `META-INF/spring.factories` 里的类名的数组
			this.propertySourceLoaders = SpringFactoriesLoader.loadFactories(
					PropertySourceLoader.class, getClass().getClassLoader());
		}

		public void load() {
			// <1> 初始化变量
			this.profiles = new LinkedList<>(); // 未处理的 Profile 集合
			this.processedProfiles = new LinkedList<>(); // 已处理的 Profile 集合
			this.activatedProfiles = false;
			this.loaded = new LinkedHashMap<>();
			MapPropertySource defaultProperties = (MapPropertySource) this.environment
					.getPropertySources().get(DEFAULT_PROPERTIES);
			replaceDefaultPropertySourceIfNecessary(defaultProperties);
			// <2> 初始化 Spring Profiles 相关
			initializeProfiles();
			// <3> 遍历 profiles 数组
			while (!this.profiles.isEmpty()) {
				// 获得 Profile 对象
				Profile profile = this.profiles.poll();
				// <3.1> 添加 Profile 到 environment 中
				if (profile != null && !profile.isDefaultProfile()) {
					addProfileToEnvironment(profile.getName());
				}
				// <3.2> 加载配置
				load(profile, this::getPositiveProfileFilter,
						addToLoaded(MutablePropertySources::addLast, false));
				// <3.3> 添加到 processedProfiles 中，表示已处理
				this.processedProfiles.add(profile);
			}
			// <4> 加载配置
			load(null, this::getNegativeProfileFilter,
					addToLoaded(MutablePropertySources::addFirst, true));
			// <5> 将加载的配置对应的 MutablePropertySources 到 environment 中
			addLoadedPropertySources();
			// <6> 获得真正加载的 Profile 们，添加到 environment 中
			resetEnvironment(defaultProperties);
		}

		private void replaceDefaultPropertySourceIfNecessary(
				MapPropertySource defaultProperties) {
			if (defaultProperties != null) {
				this.environment.getPropertySources().replace(DEFAULT_PROPERTIES,
						new FilteredDefaultPropertySource(DEFAULT_PROPERTIES,
								defaultProperties.getSource()));
			}
		}

		/**
		 * Initialize profile information from both the {@link Environment} active
		 * profiles and any {@code spring.profiles.active}/{@code spring.profiles.include}
		 * properties that are already set.
		 */
		private void initializeProfiles() {
			// The default profile for these purposes is represented as null. We add it
			// first so that it is processed first and has lowest priority.
			// <1> 添加 null 到 profiles 中。用于加载默认的配置文件。优先添加到 profiles 中，因为希望默认的配置文件先被处理。
			this.profiles.add(null);
			// <2.1> 获得激活的 Profile 们（从配置中）
			Set<Profile> activatedViaProperty = getProfilesFromProperty(
					ACTIVE_PROFILES_PROPERTY);
			// <2.1> 获得激活的 Profile 们（从配置中）
			Set<Profile> includedViaProperty = getProfilesFromProperty(
					INCLUDE_PROFILES_PROPERTY);
			List<Profile> otherActiveProfiles = getOtherActiveProfiles(
					activatedViaProperty, includedViaProperty);
			// <2.2> 先添加激活的 Profile 们（不在配置中）
			this.profiles.addAll(otherActiveProfiles);
			// Any pre-existing active profiles set via property sources (e.g.
			// System properties) take precedence over those added in config files.
			this.profiles.addAll(includedViaProperty);
			// <2.3> 再添加激活的 Profile 们（在配置中）
			addActiveProfiles(activatedViaProperty);
			// <3> 如果没有激活的 Profile 们，则添加默认的 Profile
			if (this.profiles.size() == 1) { // only has null profile
				for (String defaultProfileName : this.environment.getDefaultProfiles()) {
					Profile defaultProfile = new Profile(defaultProfileName, true);
					this.profiles.add(defaultProfile);
				}
			}
		}

		private Set<Profile> getProfilesFromProperty(String profilesProperty) {
			if (!this.environment.containsProperty(profilesProperty)) {
				return Collections.emptySet();
			}
			Binder binder = Binder.get(this.environment);
			Set<Profile> profiles = getProfiles(binder, profilesProperty);
			return new LinkedHashSet<>(profiles);
		}

		private List<Profile> getOtherActiveProfiles(Set<Profile> activatedViaProperty,
				Set<Profile> includedViaProperty) {
			return Arrays.stream(this.environment.getActiveProfiles()).map(Profile::new)
					.filter((profile) -> !activatedViaProperty.contains(profile)
							&& !includedViaProperty.contains(profile))
					.collect(Collectors.toList());
		}

		void addActiveProfiles(Set<Profile> profiles) {
			if (profiles.isEmpty()) {
				return;
			}
			if (this.activatedProfiles) {
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Profiles already activated, '" + profiles
							+ "' will not be applied");
				}
				return;
			}
			this.profiles.addAll(profiles);
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Activated activeProfiles "
						+ StringUtils.collectionToCommaDelimitedString(profiles));
			}
			this.activatedProfiles = true;
			removeUnprocessedDefaultProfiles();
		}

		private void removeUnprocessedDefaultProfiles() {
			this.profiles.removeIf(
					(profile) -> (profile != null && profile.isDefaultProfile()));
		}

		private DocumentFilter getPositiveProfileFilter(Profile profile) {
			return (Document document) -> {
				// <1> 当 profile 为空时，document.profiles 也要为空
				if (profile == null) {
					return ObjectUtils.isEmpty(document.getProfiles());
				}
				// <2> 要求 document.profiles 包含 profile
				// 并且，environment.activeProfiles 包含 document.profiles
				// 总结来说，environment.activeProfiles 包含 document.profiles 包含 profile
				return ObjectUtils.containsElement(document.getProfiles(),
						profile.getName())
						&& this.environment
								.acceptsProfiles(Profiles.of(document.getProfiles()));
			};
		}

		private DocumentFilter getNegativeProfileFilter(Profile profile) {
			// 要求传入的 profile 为空。
			return (Document document) -> (profile == null
					// 要求 document.profiles 非空
					&& !ObjectUtils.isEmpty(document.getProfiles()) && this.environment
							.acceptsProfiles(Profiles.of(document.getProfiles())));
		}

		private DocumentConsumer addToLoaded(
				BiConsumer<MutablePropertySources, PropertySource<?>> addMethod,
				boolean checkForExisting) {
			return (profile, document) -> {
				if (checkForExisting) {
					for (MutablePropertySources merged : this.loaded.values()) {
						if (merged.contains(document.getPropertySource().getName())) {
							return;
						}
					}
				}
				MutablePropertySources merged = this.loaded.computeIfAbsent(profile,
						(k) -> new MutablePropertySources());
				addMethod.accept(merged, document.getPropertySource());
			};
		}

		private void load(Profile profile, DocumentFilterFactory filterFactory,
				DocumentConsumer consumer) {
			// <1> 获得要检索配置的路径
			getSearchLocations().forEach((location) -> {
				// 判断是否为文件夹
				boolean isFolder = location.endsWith("/");
				// <2> 获得要检索配置的文件名集合
				Set<String> names = isFolder ? getSearchNames() : NO_SEARCH_NAMES;
				// <3> 遍历文件名集合，逐个加载配置文件
				names.forEach(
						(name) -> load(location, name, profile, filterFactory, consumer));
			});
		}

		private void load(String location, String name, Profile profile,
				DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			// <1> 这块逻辑先无视，因为我们不会配置 name 为空。
			// 默认情况下，name 为 DEFAULT_NAMES=application
			if (!StringUtils.hasText(name)) {
				for (PropertySourceLoader loader : this.propertySourceLoaders) {
					if (canLoadFileExtension(loader, location)) {
						load(loader, location, profile,
								filterFactory.getDocumentFilter(profile), consumer);
						return;
					}
				}
			}
			Set<String> processed = new HashSet<>(); // 已处理的文件后缀集合
			// <2> 遍历 propertySourceLoaders 数组，逐个使用 PropertySourceLoader 读取配置
			for (PropertySourceLoader loader : this.propertySourceLoaders) {
				// <3> 遍历每个 PropertySourceLoader 可处理的文件后缀集合
				for (String fileExtension : loader.getFileExtensions()) {
					// <4> 添加到 processed 中，一个文件后缀，有且仅能被一个 PropertySourceLoader 所处理
					if (processed.add(fileExtension)) {
						// 加载 Profile 指定的配置文件（带后缀）
						loadForFileExtension(loader, location + name, "." + fileExtension,
								profile, filterFactory, consumer);
					}
				}
			}
		}

		private boolean canLoadFileExtension(PropertySourceLoader loader, String name) {
			return Arrays.stream(loader.getFileExtensions())
					.anyMatch((fileExtension) -> StringUtils.endsWithIgnoreCase(name,
							fileExtension));
		}

		private void loadForFileExtension(PropertySourceLoader loader, String prefix,
				String fileExtension, Profile profile,
				DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			// <1> 获得 DocumentFilter 对象
			DocumentFilter defaultFilter = filterFactory.getDocumentFilter(null);
			DocumentFilter profileFilter = filterFactory.getDocumentFilter(profile);
			// <2> 加载 Profile 指定的配置文件（带后缀）。
			if (profile != null) {
				// Try profile-specific file & profile section in profile file (gh-340)
				// 加载 Profile 指定的配置文件（带后缀）。
				String profileSpecificFile = prefix + "-" + profile + fileExtension;
				// <2.1> 情况一，未配置 spring.profile 属性
				load(loader, profileSpecificFile, profile, defaultFilter, consumer);
				// <2.2> 情况二，有配置 spring.profile 属性
				load(loader, profileSpecificFile, profile, profileFilter, consumer);
				// Try profile specific sections in files we've already processed
				// <2.3> 特殊情况，之前读取 Profile 对应的配置文件，也可被当前 Profile 所读取。
				// 举个例子，假设之前读取了 Profile 为 common 对应配置文件是 application-common.properties ，里面配置了 spring.profile=dev,prod
				//         那么，此时如果读取的 Profile 为 dev 时，也能读取 application-common.properties 这个配置文件
				for (Profile processedProfile : this.processedProfiles) {
					if (processedProfile != null) {
						String previouslyLoaded = prefix + "-" + processedProfile
								+ fileExtension;
						load(loader, previouslyLoaded, profile, profileFilter, consumer);
					}
				}
			}
			// Also try the profile-specific section (if any) of the normal file
			// <3> 加载（无需带 Profile）指定的配置文件（带后缀）。
			load(loader, prefix + fileExtension, profile, profileFilter, consumer);
		}

		private void load(PropertySourceLoader loader, String location, Profile profile,
				DocumentFilter filter, DocumentConsumer consumer) {
			try {
				// <1.1> 判断指定的配置文件是否存在。若不存在，则直接返回
				Resource resource = this.resourceLoader.getResource(location);
				if (resource == null || !resource.exists()) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription(
								"Skipped missing config ", location, resource, profile);
						this.logger.trace(description);
					}
					return;
				}
				// <1.2> 如果没有文件后缀的配置文件，则忽略，不进行读取
				if (!StringUtils.hasText(
						StringUtils.getFilenameExtension(resource.getFilename()))) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription(
								"Skipped empty config extension ", location, resource,
								profile);
						this.logger.trace(description);
					}
					return;
				}
				// <1.3> 加载配置文件，返回 Document 数组
				String name = "applicationConfig: [" + location + "]";
				List<Document> documents = loadDocuments(loader, name, resource);
				// <1.4> 如果没加载到，则直接返回
				if (CollectionUtils.isEmpty(documents)) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription(
								"Skipped unloaded config ", location, resource, profile);
						this.logger.trace(description);
					}
					return;
				}
				// <2> 使用 DocumentFilter 过滤匹配的 Document ，添加到 loaded 数组中。
				List<Document> loaded = new ArrayList<>();
				for (Document document : documents) {
					if (filter.match(document)) {
						addActiveProfiles(document.getActiveProfiles());
						addIncludedProfiles(document.getIncludeProfiles());
						loaded.add(document);
					}
				}
				Collections.reverse(loaded);
				// <3> 使用 DocumentConsumer 进行消费 Document ，添加到本地的 loaded 中。
				if (!loaded.isEmpty()) {
					loaded.forEach((document) -> consumer.accept(profile, document));
					if (this.logger.isDebugEnabled()) {
						StringBuilder description = getDescription("Loaded config file ",
								location, resource, profile);
						this.logger.debug(description);
					}
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to load property "
						+ "source from location '" + location + "'", ex);
			}
		}

		private void addIncludedProfiles(Set<Profile> includeProfiles) {
			LinkedList<Profile> existingProfiles = new LinkedList<>(this.profiles);
			this.profiles.clear();
			this.profiles.addAll(includeProfiles);
			this.profiles.removeAll(this.processedProfiles);
			this.profiles.addAll(existingProfiles);
		}

		private List<Document> loadDocuments(PropertySourceLoader loader, String name,
				Resource resource) throws IOException {
			// <1> 创建 DocumentsCacheKey 对象，从 loadDocumentsCache 缓存中加载 Document 数组
			DocumentsCacheKey cacheKey = new DocumentsCacheKey(loader, resource);
			List<Document> documents = this.loadDocumentsCache.get(cacheKey);
			// <2.1> 如果不存在，则使用 PropertySourceLoader 加载指定配置文件
			if (documents == null) {
				List<PropertySource<?>> loaded = loader.load(name, resource);
				// <2.2> 将返回的 PropertySource 数组，封装成 Document 数组
				documents = asDocuments(loaded);
				// <2.3> 添加到 loadDocumentsCache 缓存中
				this.loadDocumentsCache.put(cacheKey, documents);
			}
			return documents;
		}

		private List<Document> asDocuments(List<PropertySource<?>> loaded) {
			if (loaded == null) {
				return Collections.emptyList();
			}
			return loaded.stream().map((propertySource) -> {
				// 创建 Binder 对象
				Binder binder = new Binder(
						ConfigurationPropertySources.from(propertySource),
						this.placeholdersResolver);
				return new Document(propertySource,
						binder.bind("spring.profiles", STRING_ARRAY).orElse(null), // 读取 "spring.profiles" 配置
						getProfiles(binder, ACTIVE_PROFILES_PROPERTY), // 读取 "spring.profiles.active" 配置
						getProfiles(binder, INCLUDE_PROFILES_PROPERTY)); // 读取 "spring.profiles.include" 配置
			}).collect(Collectors.toList());
		}

		private StringBuilder getDescription(String prefix, String location,
				Resource resource, Profile profile) {
			StringBuilder result = new StringBuilder(prefix);
			try {
				if (resource != null) {
					String uri = resource.getURI().toASCIIString();
					result.append("'");
					result.append(uri);
					result.append("' (");
					result.append(location);
					result.append(")");
				}
			}
			catch (IOException ex) {
				result.append(location);
			}
			if (profile != null) {
				result.append(" for profile ");
				result.append(profile);
			}
			return result;
		}

		private Set<Profile> getProfiles(Binder binder, String name) {
			return binder.bind(name, STRING_ARRAY).map(this::asProfileSet)
					.orElse(Collections.emptySet());
		}

		private Set<Profile> asProfileSet(String[] profileNames) {
			List<Profile> profiles = new ArrayList<>();
			for (String profileName : profileNames) {
				profiles.add(new Profile(profileName));
			}
			return new LinkedHashSet<>(profiles);
		}

		private void addProfileToEnvironment(String profile) {
			// 如果已经在 activeProfiles 中，则返回
			for (String activeProfile : this.environment.getActiveProfiles()) {
				if (activeProfile.equals(profile)) {
					return;
				}
			}
			// 如果不在，则添加到 environment 中
			this.environment.addActiveProfile(profile);
		}

		private Set<String> getSearchLocations() {
			// 获得 `"spring.config.location"` 对应的配置的值
			if (this.environment.containsProperty(CONFIG_LOCATION_PROPERTY)) {
				return getSearchLocations(CONFIG_LOCATION_PROPERTY);
			}
			// 获得 `"spring.config.additional-location"` 对应的配置的值
			Set<String> locations = getSearchLocations(
					CONFIG_ADDITIONAL_LOCATION_PROPERTY);
			// 添加 searchLocations 到 locations 中
			locations.addAll(
					asResolvedSet(ConfigFileApplicationListener.this.searchLocations,
							DEFAULT_SEARCH_LOCATIONS));
			return locations;
		}

		private Set<String> getSearchLocations(String propertyName) {
			Set<String> locations = new LinkedHashSet<>();
			// 如果 environment 中存在 propertyName 对应的值
			if (this.environment.containsProperty(propertyName)) {
				// 读取属性值，进行分割后，然后遍历
				for (String path : asResolvedSet(
						this.environment.getProperty(propertyName), null)) {
					// 处理 path
					if (!path.contains("$")) {
						path = StringUtils.cleanPath(path);
						if (!ResourceUtils.isUrl(path)) {
							path = ResourceUtils.FILE_URL_PREFIX + path;
						}
					}
					// 添加到 path 中
					locations.add(path);
				}
			}
			return locations;
		}

		private Set<String> getSearchNames() {
			// 获得 `"spring.config.name"` 对应的配置的值
			if (this.environment.containsProperty(CONFIG_NAME_PROPERTY)) {
				String property = this.environment.getProperty(CONFIG_NAME_PROPERTY);
				return asResolvedSet(property, null);
			}
			// 添加 names or DEFAULT_NAMES 到 locations 中
			return asResolvedSet(ConfigFileApplicationListener.this.names, DEFAULT_NAMES);
		}

		// 优先使用 value 。如果 value 为空，则使用 fallback
		private Set<String> asResolvedSet(String value, String fallback) {
			List<String> list = Arrays.asList(StringUtils.trimArrayElements(
					StringUtils.commaDelimitedListToStringArray((value != null)
							? this.environment.resolvePlaceholders(value) : fallback)));
			Collections.reverse(list);
			return new LinkedHashSet<>(list);
		}

		/**
		 * This ensures that the order of active profiles in the {@link Environment}
		 * matches the order in which the profiles were processed.
		 * @param processedProfiles the processed profiles
		 */
		private void resetEnvironmentProfiles(List<Profile> processedProfiles) {
			String[] names = processedProfiles.stream()
					.filter((profile) -> profile != null && !profile.isDefaultProfile())
					.map(Profile::getName).toArray(String[]::new);
			this.environment.setActiveProfiles(names);
		}

		private void addLoadedPropertySources() {
			MutablePropertySources destination = this.environment.getPropertySources();
			// 获得当前加载的 MutablePropertySources 集合
			List<MutablePropertySources> loaded = new ArrayList<>(this.loaded.values());
			// 为什么要反转一下呢？因为，配置在越后面的 Profile ，优先级越高，所以需要进行反转。
			Collections.reverse(loaded);
			// 声明变量
			String lastAdded = null; // 下面循环，最后找到的 MutablePropertySources 的名字
			Set<String> added = new HashSet<>(); // 已添加到 destination 中的 MutablePropertySources 的名字的集合
			// <X> 遍历 loaded 数组
			// 为什么是两层遍历呢？因为一个 Profile 可以对应多个配置文件。例如说，Profile 为 prod ，对应 applicaion-prod.properties 和 application-prod.yml 两个配置文件。
			// 这样，我们就可以从 environment 中，读取加载到的配置文件。
			for (MutablePropertySources sources : loaded) {
				// <Y> 遍历 sources 数组
				for (PropertySource<?> source : sources) {
					// 添加到 destination 中
					if (added.add(source.getName())) {
						addLoadedPropertySource(destination, lastAdded, source);
						lastAdded = source.getName();
					}
				}
			}
		}

		private void addLoadedPropertySource(MutablePropertySources destination,
				String lastAdded, PropertySource<?> source) {
			if (lastAdded == null) {
				if (destination.contains(DEFAULT_PROPERTIES)) {
					destination.addBefore(DEFAULT_PROPERTIES, source);
				}
				else {
					destination.addLast(source);
				}
			}
			else {
				destination.addAfter(lastAdded, source);
			}
		}

		private void resetEnvironment(MapPropertySource defaultProperties) {
			List<String> activeProfiles = new ArrayList<>();
			handleDefaultPropertySource(defaultProperties, activeProfiles);
			activeProfiles.addAll(this.processedProfiles.stream()
					.filter((profile) -> profile != null && !profile.isDefaultProfile())
					.map(Profile::getName).collect(Collectors.toList()));
			this.environment.setActiveProfiles(activeProfiles.toArray(new String[0]));
		}

		private void handleDefaultPropertySource(MapPropertySource defaultProperties,
				List<String> activeProfiles) {
			if (defaultProperties != null) {
				Binder binder = new Binder(
						ConfigurationPropertySources.from(defaultProperties),
						new PropertySourcesPlaceholdersResolver(this.environment));
				List<String> includes = getDefaultProfiles(binder,
						"spring.profiles.include");
				activeProfiles.addAll(includes);
				if (!this.activatedProfiles) {
					List<String> active = getDefaultProfiles(binder,
							"spring.profiles.active");
					activeProfiles.addAll(active);
				}
				this.environment.getPropertySources().replace(DEFAULT_PROPERTIES,
						defaultProperties);
			}
		}

		private List<String> getDefaultProfiles(Binder binder, String property) {
			return Arrays
					.asList(binder.bind(property, STRING_ARRAY).orElse(new String[] {}));
		}

	}

	private static class FilteredDefaultPropertySource extends MapPropertySource {

		private static final List<String> FILTERED_PROPERTY = Arrays
				.asList("spring.profiles.active", "spring.profiles.include");

		FilteredDefaultPropertySource(String name, Map<String, Object> source) {
			super(name, source);
		}

		@Override
		public Object getProperty(String name) {
			if (isFilteredProperty(name)) {
				return null;
			}
			return super.getProperty(name);
		}

		@Override
		public boolean containsProperty(String name) {
			if (isFilteredProperty(name)) {
				return false;
			}
			return super.containsProperty(name);
		}

		@Override
		public String[] getPropertyNames() {
			return Arrays.stream(super.getPropertyNames())
					.filter((name) -> !isFilteredProperty(name)).toArray(String[]::new);
		}

		protected boolean isFilteredProperty(String name) {
			return FILTERED_PROPERTY.contains(name);
		}

	}

	/**
	 * A Spring Profile that can be loaded.
	 */
	private static class Profile {

		private final String name;

		private final boolean defaultProfile;

		Profile(String name) {
			this(name, false);
		}

		Profile(String name, boolean defaultProfile) {
			Assert.notNull(name, "Name must not be null");
			this.name = name;
			this.defaultProfile = defaultProfile;
		}

		public String getName() {
			return this.name;
		}

		public boolean isDefaultProfile() {
			return this.defaultProfile;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (obj == null || obj.getClass() != getClass()) {
				return false;
			}
			return ((Profile) obj).name.equals(this.name);
		}

		@Override
		public int hashCode() {
			return this.name.hashCode();
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * Cache key used to save loading the same document multiple times.
	 */
	private static class DocumentsCacheKey {

		private final PropertySourceLoader loader;

		private final Resource resource;

		DocumentsCacheKey(PropertySourceLoader loader, Resource resource) {
			this.loader = loader;
			this.resource = resource;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			DocumentsCacheKey other = (DocumentsCacheKey) obj;
			return this.loader.equals(other.loader)
					&& this.resource.equals(other.resource);
		}

		@Override
		public int hashCode() {
			return this.loader.hashCode() * 31 + this.resource.hashCode();
		}

	}

	/**
	 * A single document loaded by a {@link PropertySourceLoader}.
	 */
	private static class Document {

		private final PropertySource<?> propertySource;

		/**
		 * 对应 `spring.profiles` 属性值
		 */
		private String[] profiles;

		/**
		 * 对应 `spring.profiles.active` 属性值
		 */
		private final Set<Profile> activeProfiles;

		/**
		 * 对应 `spring.profiles.include` 属性值
		 */
		private final Set<Profile> includeProfiles;

		Document(PropertySource<?> propertySource, String[] profiles,
				Set<Profile> activeProfiles, Set<Profile> includeProfiles) {
			this.propertySource = propertySource;
			this.profiles = profiles;
			this.activeProfiles = activeProfiles;
			this.includeProfiles = includeProfiles;
		}

		public PropertySource<?> getPropertySource() {
			return this.propertySource;
		}

		public String[] getProfiles() {
			return this.profiles;
		}

		public Set<Profile> getActiveProfiles() {
			return this.activeProfiles;
		}

		public Set<Profile> getIncludeProfiles() {
			return this.includeProfiles;
		}

		@Override
		public String toString() {
			return this.propertySource.toString();
		}

	}

	/**
	 * Factory used to create a {@link DocumentFilter}.
	 */
	@FunctionalInterface
	private interface DocumentFilterFactory {

		/**
		 * Create a filter for the given profile.
		 * @param profile the profile or {@code null}
		 * @return the filter
		 */
		DocumentFilter getDocumentFilter(Profile profile);

	}

	/**
	 * todo： 两个实现看不懂
	 * Filter used to restrict when a {@link Document} is loaded.
	 */
	@FunctionalInterface
	private interface DocumentFilter {

		boolean match(Document document);

	}

	/**
	 * Consumer used to handle a loaded {@link Document}.
	 */
	@FunctionalInterface
	private interface DocumentConsumer {

		void accept(Profile profile, Document document);

	}

}
