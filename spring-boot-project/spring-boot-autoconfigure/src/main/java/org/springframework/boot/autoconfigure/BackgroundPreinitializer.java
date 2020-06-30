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

package org.springframework.boot.autoconfigure;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.validation.Configuration;
import javax.validation.Validation;

import org.apache.catalina.mbeans.MBeanFactory;

import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;

/**
 * {@link ApplicationListener} to trigger early initialization in a background thread of
 * time consuming tasks.
 * <p>
 * Set the {@link #IGNORE_BACKGROUNDPREINITIALIZER_PROPERTY_NAME} system property to
 * {@code true} to disable this mechanism and let such initialization happen in the
 * foreground.
 *
 * 实现后台提前执行耗时的初始化任务。
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Artsiom Yudovin
 * @since 1.3.0
 */
@Order(LoggingApplicationListener.DEFAULT_ORDER + 1)
public class BackgroundPreinitializer
		implements ApplicationListener<SpringApplicationEvent> {

	/**
	 * System property that instructs Spring Boot how to run pre initialization. When the
	 * property is set to {@code true}, no pre-initialization happens and each item is
	 * initialized in the foreground as it needs to. When the property is {@code false}
	 * (default), pre initialization runs in a separate thread in the background.
	 * @since 2.1.0
	 */
	public static final String IGNORE_BACKGROUNDPREINITIALIZER_PROPERTY_NAME = "spring.backgroundpreinitializer.ignore";

	/**
	 * 预初始化任务是否已启动
	 */
	private static final AtomicBoolean preinitializationStarted = new AtomicBoolean(
			false);

	/**
	 * 预初始化任务的 CountDownLatch 对象，用于实现等待预初始化任务是否完成
	 */
	private static final CountDownLatch preinitializationComplete = new CountDownLatch(1);

	@Override
	public void onApplicationEvent(SpringApplicationEvent event) {
		// <1> 如果是开启后台预初始化任务，默认情况下开启
		// 并且，是 ApplicationStartingEvent 事件，说明应用正在启动中
		// 并且，是多核环境
		// 并且，预初始化任务未启动
		if (!Boolean.getBoolean(IGNORE_BACKGROUNDPREINITIALIZER_PROPERTY_NAME)
				&& event instanceof ApplicationStartingEvent && multipleProcessors()
				&& preinitializationStarted.compareAndSet(false, true)) {
			performPreinitialization();
		}
		// <2> 如果是 ApplicationReadyEvent 或 ApplicationFailedEvent 事件，说明应用启动成功后失败，则等待预初始化任务完成
		if ((event instanceof ApplicationReadyEvent
				|| event instanceof ApplicationFailedEvent)
				&& preinitializationStarted.get()) {
			// 通过 CountDownLatch 实现，预初始化任务执行完成。
			try {
				preinitializationComplete.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private boolean multipleProcessors() {
		return Runtime.getRuntime().availableProcessors() > 1;
	}

	private void performPreinitialization() {
		try {
			// <1> 创建线程
			Thread thread = new Thread(new Runnable() {

				@Override
				public void run() {
					// 安全运行每个初始化任务
					runSafely(new ConversionServiceInitializer());
					runSafely(new ValidationInitializer());
					runSafely(new MessageConverterInitializer());
					runSafely(new MBeanFactoryInitializer());
					runSafely(new JacksonInitializer());
					runSafely(new CharsetInitializer());
					// <3> 标记 preinitializationComplete 完成
					preinitializationComplete.countDown();
				}

				public void runSafely(Runnable runnable) {
					try {
						runnable.run();
					}
					catch (Throwable ex) {
						// Ignore
					}
				}

			}, "background-preinit");
			// <2> 启动线程
			thread.start();
		}
		catch (Exception ex) {
			// This will fail on GAE where creating threads is prohibited. We can safely
			// continue but startup will be slightly slower as the initialization will now
			// happen on the main thread.
			preinitializationComplete.countDown();
		}
	}

	/**
	 * Early initializer for Spring MessageConverters.
	 */
	private static class MessageConverterInitializer implements Runnable {

		@Override
		public void run() {
			new AllEncompassingFormHttpMessageConverter();
		}

	}

	/**
	 * Early initializer to load Tomcat MBean XML.
	 */
	private static class MBeanFactoryInitializer implements Runnable {

		@Override
		public void run() {
			new MBeanFactory();
		}

	}

	/**
	 * Early initializer for javax.validation.
	 */
	private static class ValidationInitializer implements Runnable {

		@Override
		public void run() {
			Configuration<?> configuration = Validation.byDefaultProvider().configure();
			configuration.buildValidatorFactory().getValidator();
		}

	}

	/**
	 * Early initializer for Jackson.
	 */
	private static class JacksonInitializer implements Runnable {

		@Override
		public void run() {
			Jackson2ObjectMapperBuilder.json().build();
		}

	}

	/**
	 * Early initializer for Spring's ConversionService.
	 */
	private static class ConversionServiceInitializer implements Runnable {

		@Override
		public void run() {
			new DefaultFormattingConversionService();
		}

	}

	private static class CharsetInitializer implements Runnable {

		@Override
		public void run() {
			StandardCharsets.UTF_8.name();
		}

	}

}
