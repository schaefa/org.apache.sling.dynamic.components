package org.apache.sling.dynamic.core.test;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.dynamic.core.DynamicComponentResourceManager;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component(
    service= DynamicComponentSetup.class,
    immediate = true
)
@Designate(ocd = DynamicComponentSetupService.Configuration.class, factory = true)
public class DynamicComponentSetupService
    implements DynamicComponentSetup {
    @ObjectClassDefinition(
        name = "Dynamic Component Setup",
        description = "Configuration of the Setup for Dynamic Component Resource Provider")
    public @interface Configuration {
        @AttributeDefinition(
            name = "Dynamic Root Path",
            description = "Dynamic Location Root Path")
        String dynamic_component_root_path() default "/conf/wknd/settings";
        @AttributeDefinition(
            name = "Component Group of the Dynamic Components",
            description = "Component Group Name")
        String dynamic_component_group() default "WKND.Content";
        @AttributeDefinition(
            name = "Primary Group",
            description = "Component Primary Type")
        String dynamic_component_primary_type() default "cq:Component";
        @AttributeDefinition(
            name = "List of Dynamic Components",
            description = "Dynamic Component Definitions in format: <name>=<title>:<super resource type>")
        String[] dynamic_component_names() default "button=Button-default:core/wcm/components/button/v1/button";
        @AttributeDefinition(
            name = "Additional Properties for Dynamic Components",
            description = "Dynamic Component Additional Properties in format: <name>=<property name>|<property value>")
        String[] dynamic_component_additional_properties() default "";
    }

    public static final String DYNAMIC_COMPONENT_FOLDER_NAME = "dynamic";

    private static final String WKND_COMPONENTS_FOLDER_PATH = "/apps/wknd/components";
    private static final String DYNAMIC_COMPONENTS_FOLDER_PATH = "/apps/dynamicComponents/components";

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    DynamicComponentResourceManager dynamicComponentResourceManager;

    private BundleContext bundleContext;

    @Activate
    private void activate(BundleContext bundleContext, Configuration configuration) {
        log.info("Activate Started, bundle context: '{}'", bundleContext);
        this.bundleContext = bundleContext;
        final String rootPath = configuration.dynamic_component_root_path();
        final String group = configuration.dynamic_component_group();
        final String primaryType = configuration.dynamic_component_primary_type();
        try (ResourceResolver resourceResolver = resourceResolverFactory.getAdministrativeResourceResolver(null)) {
            Resource root = resourceResolver.getResource(rootPath);
            if(root == null) {
                throw new IllegalArgumentException("Root Path: '" + rootPath + "' does not exist");
            }
            Resource target = root.getChild(DYNAMIC_COMPONENT_FOLDER_NAME);
            log.info("Dynamic Folder looked up: '{}'", target);
            if(target == null) {
                target = resourceResolver.create(root, DYNAMIC_COMPONENT_FOLDER_NAME, new HashMap<String, Object>() {{
                        put("jcr:primaryType", "sling:Folder");
                    }}
                );
                resourceResolver.commit();
                log.info("Dynamic Folder created: '{}'", target);
            } else {
                // Remove any existing children
                Iterator<Resource> i = target.listChildren();
                while(i.hasNext()) {
                    resourceResolver.delete(i.next());
                }
                resourceResolver.commit();
            }
            Map<String, List<Property>> additionalProperties = new HashMap<>();
            for(String additionalProperty: configuration.dynamic_component_additional_properties()) {
                String[] split = additionalProperty.split("=");
                if(split.length != 2) {
                    throw new IllegalArgumentException("Dynamic Additional Property is invalid (split on = does not yield 2 tokens): " + additionalProperty);
                }
                String componentName = split[0];
                String temp = split[1];
                log.info("Component Name: '{}', rest: '{}'", componentName, temp);
                String[] temps2 = temp.split("\\|");
                if(split.length != 2) {
                    throw new IllegalArgumentException("Dynamic Additional Property is invalid (split on | does not yield 2 tokens): " + temp);
                }
                String propertyName = temps2[0];
                String propertyValue = temps2[1];
                log.info("Property Name: '{}', Value: '{}'", propertyName, propertyValue);
                Property value;
                if(propertyValue.charAt(0) == '{' && propertyValue.charAt(propertyValue.length() - 1) == '}') {
                    String[] entries = propertyValue.substring(1, propertyValue.length() - 1).split(";");
                    value = new Property(propertyName, Arrays.asList(entries));
                } else {
                    value = new Property(propertyName, propertyValue);
                }
                List<Property> propertyList = additionalProperties.get(componentName);
                if(propertyList == null) {
                    propertyList = new ArrayList<>();
                    additionalProperties.put(componentName, propertyList);
                }
                propertyList.add(value);
            }
            for (String dynamicComponentName : configuration.dynamic_component_names()) {
                String[] split = dynamicComponentName.split("=");
                if(split.length != 2) {
                    throw new IllegalArgumentException("Dynamic Configuration Name is invalid (split on = does not yield 2 tokens): " + dynamicComponentName);
                }
                String name = split[0];
                String split1 = split[1];
                split = split1.split(":");
                if(split.length != 2) {
                    throw new IllegalArgumentException("Dynamic Configuration Name is invalid (split on : does not yield 2 tokens): " + split1);
                }
                final String title = split[0];
                final String resourceSuperType = split[1];

                Map<String, Object> props = new HashMap<String, Object>() {{
                    put("componentGroup", group);
                    put("jcr:primaryType", primaryType);
                    put("jcr:title", title);
                    put("sling:resourceSuperType", resourceSuperType);
                }};
                List<Property> propertyList = additionalProperties.get(name);
                log.info("Component: '{}', property list: '{}'", name, propertyList);
                if(propertyList != null) {
                    for (Property property : propertyList) {
                        if(property.isSingle()) {
                            props.put(property.getName(), property.getValue());
                        } else {
                            log.info("Add Property as Multi-Value: '{}'", property.getValues());
                            props.put(property.getName(), property.getValues().toArray());
                        }
                    }
                }
                log.info("Props for to be created Node: '{}'", props);
                Resource newTarget = resourceResolver.create(target, name, props);
                log.info("Newly Created Target: '{}'", newTarget);
            }
            resourceResolver.commit();
            log.info("Update the Dynamic Component Resource Manager with Provider Path: '{}'", target.getPath());
            dynamicComponentResourceManager.update(target.getPath());
            // Now test the setup
            Resource button = resourceResolver.getResource(WKND_COMPONENTS_FOLDER_PATH + "/" + "button");
            log.info("Static Button: '{}'", button);
            Resource button1 = resourceResolver.getResource(DYNAMIC_COMPONENTS_FOLDER_PATH + "/" + "button1");
            log.info("Dynamic Button 1: '{}'", button1);
            Resource container = resourceResolver.getResource(WKND_COMPONENTS_FOLDER_PATH + "/" + "container");
            log.info("Static Container: '{}'", container);
            Resource container1 = resourceResolver.getResource(DYNAMIC_COMPONENTS_FOLDER_PATH + "/" + "container1");
            log.info("Dynamic Container 1: '{}'", container1);
            Iterator<Resource> i = resourceResolver.listChildren(container1.getParent());
            int index = 0;
            while(i.hasNext()) {
                log.info("{}. Entry: '{}'", index++, i.next());
            }
        } catch (LoginException e) {
            log.error("Cannot Access Resource Resolver", e);
        } catch (PersistenceException e) {
            log.error("Failed to create Dynamic Component", e);
        }
    }

    private static class Property {
        private String name;
        private String value;
        private List<String> values;

        public Property(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public Property(String name, List<String> values) {
            this.name = name;
            this.values = values;
        }

        public boolean isSingle() {
            return value != null;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public List<String> getValues() {
            return values;
        }

        @Override
        public String toString() {
            return "Property{" +
                "name='" + name + '\'' +
                ", value='" + value + '\'' +
                ", values=" + values +
                '}';
        }
    }
}