/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.xd.dirt.server;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.Assert;
import org.springframework.xd.dirt.core.Stream;
import org.springframework.xd.dirt.integration.bus.BusProperties;
import org.springframework.xd.module.ModuleDeploymentProperties;
import org.springframework.xd.module.ModuleDescriptor;
import org.springframework.xd.module.RuntimeModuleDeploymentProperties;


/**
 * {@link RuntimeModuleDeploymentPropertiesProvider} that provides the stream partition/short circuit
 * properties for the given {@link ModuleDescriptor}.
 *
 * @author Patrick Peralta
 * @author Mark Fisher
 * @author Ilayaperumal Gopinathan
 * @author Marius Bogoevici
 */
public class StreamRuntimePropertiesProvider extends RuntimeModuleDeploymentPropertiesProvider {

	/**
	 * Logger.
	 */
	private static final Logger logger = LoggerFactory.getLogger(StreamRuntimePropertiesProvider.class);

	/**
	 * The stream to create properties for.
	 */
	private final Stream stream;

	/**
	 * Construct a {@code StreamPartitionPropertiesProvider} for
	 * a {@link org.springframework.xd.dirt.core.Stream}.
	 *
	 * @param stream stream to create partition properties for
	 */
	public StreamRuntimePropertiesProvider(Stream stream,
			ModuleDeploymentPropertiesProvider<ModuleDeploymentProperties> propertiesProvider) {
		super(propertiesProvider);
		this.stream = stream;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public RuntimeModuleDeploymentProperties propertiesForDescriptor(ModuleDescriptor moduleDescriptor) {
		List<ModuleDescriptor> streamModules = stream.getModuleDescriptors();
		RuntimeModuleDeploymentProperties properties = super.propertiesForDescriptor(moduleDescriptor);
		int moduleSequence = properties.getSequence();
		int moduleIndex = moduleDescriptor.getIndex();

		// Not first
		if (moduleIndex > 0) {
			ModuleDescriptor previous = streamModules.get(moduleIndex - 1);
			ModuleDeploymentProperties previousProperties = deploymentPropertiesProvider.propertiesForDescriptor(previous);
			properties.put("consumer." + BusProperties.SEQUENCE, String.valueOf(moduleSequence));
			properties.put("consumer." + BusProperties.COUNT, String.valueOf(properties.getCount()));
			if (hasPartitionKeyProperty(previousProperties)) {
				properties.put("consumer." + BusProperties.PARTITION_INDEX, String.valueOf(moduleSequence - 1));
			}
		}
		// Not last
		if (moduleIndex + 1 < streamModules.size()) {
			ModuleDeploymentProperties nextProperties = deploymentPropertiesProvider.propertiesForDescriptor(streamModules.get(moduleIndex + 1));
			String count = nextProperties.get("count");
			if (count != null) {
				properties.put("producer." + BusProperties.NEXT_MODULE_COUNT, count);
			}
			String concurrency = nextProperties.get(BusProperties.CONCURRENCY);
			if (concurrency != null) {
				properties.put("producer." + BusProperties.NEXT_MODULE_CONCURRENCY, concurrency);
			}
		}

		// Partitioning
		if (hasPartitionKeyProperty(properties)) {
			try {
				ModuleDeploymentProperties nextProperties =
						deploymentPropertiesProvider.propertiesForDescriptor(streamModules.get(moduleIndex + 1));

				String count = nextProperties.get("count");
				validateCountProperty(count, moduleDescriptor);
				properties.put("producer." + BusProperties.PARTITION_COUNT, count);
			}
			catch (IndexOutOfBoundsException e) {
				logger.warn("Module '{}' is a sink module which contains a property " +
						"of '{}' used for data partitioning; this feature is only " +
						"supported for modules that produce data", moduleDescriptor,
						"producer.partitionKeyExpression");

			}
		}
		else if (moduleIndex + 1 < streamModules.size()) {
			ModuleDeploymentProperties nextProperties = deploymentPropertiesProvider.propertiesForDescriptor(streamModules.get(moduleIndex + 1));
			/*
			 *  A direct binding is allowed if all of the following are true:
			 *  1. the user did not explicitly disallow direct binding
			 *  2. this module is not a partitioning producer
			 *  3. this module is not the last one in a stream
			 *  4. both this module and the next module have a count of 0
			 *  5. both this module and the next module have the same criteria (both can be null)
			 */
			String directBindingKey = "producer." + BusProperties.DIRECT_BINDING_ALLOWED;
			String directBindingValue = properties.get(directBindingKey);
			if (directBindingValue != null && !"false".equalsIgnoreCase(directBindingValue)) {
				logger.warn(
						"Only 'false' is allowed as an explicit value for the {} property,  but the value was: '{}'",
						directBindingKey, directBindingValue);
			}
			if (!"false".equalsIgnoreCase(properties.get(directBindingKey))) {
				if (properties.getCount() == 0 && nextProperties.getCount() == 0) {
					String criteria = properties.getCriteria();
					if ((criteria == null && nextProperties.getCriteria() == null)
							|| (criteria != null && criteria.equals(nextProperties.getCriteria()))) {
						properties.put(directBindingKey, Boolean.toString(true));
					}
				}
			}
		}
		return properties;
	}

	/**
	 * Return {@code true} if the provided properties include a property
	 * used to extract a partition key.
	 *
	 * @param properties properties to examine for a partition key property
	 * @return true if the properties contain a partition key property
	 */
	private boolean hasPartitionKeyProperty(ModuleDeploymentProperties properties) {
		return (properties.containsKey("producer.partitionKeyExpression") || properties.containsKey("producer.partitionKeyExtractorClass"));
	}

	/**
	 * Validate the value of {@code count} for the purposes of partitioning.
	 * The value of the string must consist of an integer > 1.
	 *
	 * @param count       value to validate
	 * @param descriptor  module descriptor this {@code count} property
	 *                    is associated with
	 *
	 * @throws IllegalArgumentException if the value of the string
	 *         does not consist of an integer > 1
	 */
	private void validateCountProperty(String count, ModuleDescriptor descriptor) {
		Assert.hasText(count, String.format("'count' property is required " +
				"in properties for module '%s' in order to support partitioning", descriptor));

		try {
			Assert.isTrue(Integer.parseInt(count) > 1,
					String.format("'count' property for module '%s' must contain an " +
							"integer > 1, current value is '%s'", descriptor, count));
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException(String.format("'count' property for " +
					"module %s does not contain a valid integer, current value is '%s'",
					descriptor, count));
		}
	}

}
