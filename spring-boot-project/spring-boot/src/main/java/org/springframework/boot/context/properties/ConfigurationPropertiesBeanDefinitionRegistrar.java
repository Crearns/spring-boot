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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.core.KotlinDetector;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Registers a bean definition for a type annotated with {@link ConfigurationProperties}
 * using the prefix of the annotation in the bean name.
 *
 * @author Madhura Bhave
 */
final class ConfigurationPropertiesBeanDefinitionRegistrar {

	private static final boolean KOTLIN_PRESENT = KotlinDetector.isKotlinPresent();

	private ConfigurationPropertiesBeanDefinitionRegistrar() {
	}

	public static void register(BeanDefinitionRegistry registry,
			ConfigurableListableBeanFactory beanFactory, Class<?> type) {
		// <2.1> 通过 @ConfigurationProperties 注解，获得最后要生成的 BeanDefinition 的名字。格式为 prefix-类全名 or 类全名
		String name = getName(type);
		// <2.2> 判断是否已经有该名字的 BeanDefinition 的名字。没有，才进行注册
		if (!containsBeanDefinition(beanFactory, name)) {
			registerBeanDefinition(registry, beanFactory, name, type);
		}
	}

	private static String getName(Class<?> type) {
		ConfigurationProperties annotation = AnnotationUtils.findAnnotation(type,
				ConfigurationProperties.class);
		String prefix = (annotation != null) ? annotation.prefix() : "";
		return (StringUtils.hasText(prefix) ? prefix + "-" + type.getName()
				: type.getName());
	}

	private static boolean containsBeanDefinition(
			ConfigurableListableBeanFactory beanFactory, String name) {
		// 判断是否存在 BeanDefinition 。如果有，则返回 true
		if (beanFactory.containsBeanDefinition(name)) {
			return true;
		}
		// 获得父容器，判断是否存在
		BeanFactory parent = beanFactory.getParentBeanFactory();
		if (parent instanceof ConfigurableListableBeanFactory) {
			return containsBeanDefinition((ConfigurableListableBeanFactory) parent, name);
		}
		// 返回 false ，说明不存在
		return false;
	}

	private static void registerBeanDefinition(BeanDefinitionRegistry registry,
			ConfigurableListableBeanFactory beanFactory, String name, Class<?> type) {
		// 断言，判断该类有 @ConfigurationProperties 注解 防御式编程
		assertHasAnnotation(type);
		registry.registerBeanDefinition(name,
				createBeanDefinition(beanFactory, name, type));
	}

	private static void assertHasAnnotation(Class<?> type) {
		Assert.notNull(
				AnnotationUtils.findAnnotation(type, ConfigurationProperties.class),
				() -> "No " + ConfigurationProperties.class.getSimpleName()
						+ " annotation found on  '" + type.getName() + "'.");
	}

	private static BeanDefinition createBeanDefinition(
			ConfigurableListableBeanFactory beanFactory, String name, Class<?> type) {
		if (canBindAtCreationTime(type)) {
			return ConfigurationPropertiesBeanDefinition.from(beanFactory, name, type);
		}
		else {
			// 创建 GenericBeanDefinition 对象
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setBeanClass(type);
			return definition;
		}
	}

	private static boolean canBindAtCreationTime(Class<?> type) {
		List<Constructor<?>> constructors = determineConstructors(type);
		return (constructors.size() == 1 && constructors.get(0).getParameterCount() > 0);
	}

	private static List<Constructor<?>> determineConstructors(Class<?> type) {
		List<Constructor<?>> constructors = new ArrayList<>();
		if (KOTLIN_PRESENT && KotlinDetector.isKotlinType(type)) {
			Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(type);
			if (primaryConstructor != null) {
				constructors.add(primaryConstructor);
			}
		}
		else {
			constructors.addAll(Arrays.asList(type.getDeclaredConstructors()));
		}
		return constructors;
	}

}
