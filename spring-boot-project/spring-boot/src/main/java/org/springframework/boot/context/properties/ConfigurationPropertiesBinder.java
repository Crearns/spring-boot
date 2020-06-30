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

package org.springframework.boot.context.properties;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.handler.IgnoreErrorsBindHandler;
import org.springframework.boot.context.properties.bind.handler.IgnoreTopLevelConverterNotFoundBindHandler;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.PropertySources;
import org.springframework.util.Assert;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;

/**
 * Internal class by the {@link ConfigurationPropertiesBindingPostProcessor} to handle the
 * actual {@link ConfigurationProperties} binding.
 *
 * @author Stephane Nicoll
 * @author Phillip Webb
 */
class ConfigurationPropertiesBinder implements ApplicationContextAware {

	/**
	 * The bean name that this binder is registered with.
	 */
	static final String BEAN_NAME = "org.springframework.boot.context.internalConfigurationPropertiesBinder";

	private final String validatorBeanName;

	private ApplicationContext applicationContext;

	private PropertySources propertySources;

	private Validator configurationPropertiesValidator;

	private boolean jsr303Present;

	private volatile Validator jsr303Validator;

	private volatile Binder binder;

	ConfigurationPropertiesBinder(String validatorBeanName) {
		this.validatorBeanName = validatorBeanName;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
		// 设置 applicationContext 属性。
		this.applicationContext = applicationContext;
		// 创建 org.springframework.boot.context.properties.PropertySourcesDeducer 对象
		// 然后调用 PropertySourcesDeducer#getPropertySources() 方法，获得 PropertySource 数组
		// 之后设置给 propertySources 属性。
		this.propertySources = new PropertySourcesDeducer(applicationContext)
				.getPropertySources();
		// 调用 #getConfigurationPropertiesValidator(ApplicationContext applicationContext, String validatorBeanName) 方法
		// 获得配置的 Validator 对象。
		this.configurationPropertiesValidator = getConfigurationPropertiesValidator(
				applicationContext, this.validatorBeanName);
		// 调用 ConfigurationPropertiesJsr303Validator#isJsr303Present(ApplicationContext applicationContext) 方法
		// 是否有引入 Jsr 303 Validator 相关的依赖。
		this.jsr303Present = ConfigurationPropertiesJsr303Validator
				.isJsr303Present(applicationContext);
	}

	public <T> BindResult<T> bind(Bindable<T> target) {
		// <1> 获得 @ConfigurationProperties 注解的属性
		ConfigurationProperties annotation = target
				.getAnnotation(ConfigurationProperties.class);
		Assert.state(annotation != null,
				() -> "Missing @ConfigurationProperties on " + target);
		// <2> 获得 Validator 数组
		List<Validator> validators = getValidators(target);
		// <3> 获得 BindHandler 对象
		BindHandler bindHandler = getBindHandler(annotation, validators);
		// <4> 获得 Binder 对象，然后执行绑定逻辑，处理 `@ConfigurationProperties` 注解的 Bean 的属性的注入
		return getBinder().bind(annotation.prefix(), target, bindHandler);
	}

	private Validator getConfigurationPropertiesValidator(
			ApplicationContext applicationContext, String validatorBeanName) {
		if (applicationContext.containsBean(validatorBeanName)) {
			return applicationContext.getBean(validatorBeanName, Validator.class);
		}
		return null;
	}

	private List<Validator> getValidators(Bindable<?> target) {
		List<Validator> validators = new ArrayList<>(3);
		// 来源一，configurationPropertiesValidator
		if (this.configurationPropertiesValidator != null) {
			validators.add(this.configurationPropertiesValidator);
		}
		// 来源二，ConfigurationPropertiesJsr303Validator 对象
		if (this.jsr303Present && target.getAnnotation(Validated.class) != null) {
			validators.add(getJsr303Validator());
		}
		// 来源三，自己实现了 Validator 接口
		if (target.getValue() != null && target.getValue().get() instanceof Validator) {
			validators.add((Validator) target.getValue().get());
		}
		return validators;
	}

	// 返回 ConfigurationPropertiesJsr303Validator 对象
	private Validator getJsr303Validator() {
		if (this.jsr303Validator == null) {
			this.jsr303Validator = new ConfigurationPropertiesJsr303Validator(
					this.applicationContext);
		}
		return this.jsr303Validator;
	}

	private BindHandler getBindHandler(ConfigurationProperties annotation,
			List<Validator> validators) {
		BindHandler handler = new IgnoreTopLevelConverterNotFoundBindHandler();
		// 如果有 ignoreInvalidFields 属性，进一步包装成 IgnoreErrorsBindHandler 类
		if (annotation.ignoreInvalidFields()) {
			handler = new IgnoreErrorsBindHandler(handler);
		}
		// 如果否 ignoreUnknownFields 属性，进一步包装成 NoUnboundElementsBindHandler 类
		if (!annotation.ignoreUnknownFields()) {
			UnboundElementsSourceFilter filter = new UnboundElementsSourceFilter();
			handler = new NoUnboundElementsBindHandler(handler, filter);
		}
		// <X> 如果 Validator 数组非空，进一步包装成 ValidationBindHandler 对象
		if (!validators.isEmpty()) {
			handler = new ValidationBindHandler(handler,
					validators.toArray(new Validator[0]));
		}
		// <Y> 如果有 ConfigurationPropertiesBindHandlerAdvisor 元素，则进一步处理 handler 对象
		for (ConfigurationPropertiesBindHandlerAdvisor advisor : getBindHandlerAdvisors()) {
			handler = advisor.apply(handler);
		}
		return handler;
	}

	private List<ConfigurationPropertiesBindHandlerAdvisor> getBindHandlerAdvisors() {
		return this.applicationContext
				.getBeanProvider(ConfigurationPropertiesBindHandlerAdvisor.class)
				.orderedStream().collect(Collectors.toList());
	}

	private Binder getBinder() {
		if (this.binder == null) {
			this.binder = new Binder(getConfigurationPropertySources(),
					getPropertySourcesPlaceholdersResolver(), getConversionService(),
					getPropertyEditorInitializer());
		}
		return this.binder;
	}

	private Iterable<ConfigurationPropertySource> getConfigurationPropertySources() {
		return ConfigurationPropertySources.from(this.propertySources);
	}

	private PropertySourcesPlaceholdersResolver getPropertySourcesPlaceholdersResolver() {
		return new PropertySourcesPlaceholdersResolver(this.propertySources);
	}

	private ConversionService getConversionService() {
		return new ConversionServiceDeducer(this.applicationContext)
				.getConversionService();
	}

	private Consumer<PropertyEditorRegistry> getPropertyEditorInitializer() {
		if (this.applicationContext instanceof ConfigurableApplicationContext) {
			return ((ConfigurableApplicationContext) this.applicationContext)
					.getBeanFactory()::copyRegisteredEditorsTo;
		}
		return null;
	}

}
