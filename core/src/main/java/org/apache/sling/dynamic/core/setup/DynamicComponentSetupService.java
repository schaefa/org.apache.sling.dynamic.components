package org.apache.sling.dynamic.core.setup;

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
        @AttributeDefinition(
            name = "References for Dynamic Components",
            description = "Dynamic Component Reference in format: <name>=<path>")
        String[] dynamic_component_refs() default "";
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
                Property component = new Property(additionalProperty, "Dynamic Additional Property");
                if(!component.isComponent()) {
                    throw new IllegalArgumentException("Addition Properties is not a component: '" + additionalProperty + "'");
                }
                addItemToListMap(additionalProperties, component);
            }
            Map<String, List<Property>> dynamicRefs = new HashMap<>();
            for(String ref: configuration.dynamic_component_refs()) {
                Property component = new Property(ref, "Dynamic Ref");
                if(!component.isComponent()) {
                    throw new IllegalArgumentException("Dynamic Ref is not a component: '" + ref + "'");
                }
                addItemToListMap(dynamicRefs, component);
            }
            log.info("Dynamic Refs: '{}'", dynamicRefs);
            for (String dynamicComponentName : configuration.dynamic_component_names()) {
                final Property dynamicComponent = new Property(dynamicComponentName, "Dynamic Component");
                if(!dynamicComponent.isComponent()) {
                    throw new IllegalArgumentException("Dynamic Configuration Name is invalid (split on = does not yield 2 tokens): " + dynamicComponentName);
                }
                Map<String, Object> props = new HashMap<String, Object>() {{
                    put("componentGroup", group);
                    put("jcr:primaryType", primaryType);
                    put("jcr:title", dynamicComponent.getName());
                    put("sling:resourceSuperType", dynamicComponent.getValue());
                }};
                List<Property> propertyList = additionalProperties.get(dynamicComponent.getComponent());
                log.info("Component: '{}', property list: '{}'", dynamicComponent.getComponent(), propertyList);
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
                Resource newTarget = resourceResolver.create(target, dynamicComponent.getComponent(), props);
                log.info("Newly Created Target: '{}'", newTarget);
                // Add Dynamic Refs
                List<Property> refs = dynamicRefs.get(dynamicComponent.getComponent());
                if(refs != null) {
                    for (final Property ref : refs) {
                        Map<String, Object> refProps = new HashMap<String, Object>() {{
                            put("jcr:primaryType", "nt:unstructured");
                            put("jcr:title", ref.getName());
                            put("ref", ref.getValue());
                        }};
                        Resource newRef = resourceResolver.create(
                            newTarget, ref.getName(), refProps
                        );
                    }
                }
            }
            resourceResolver.commit();
            log.info("Update the Dynamic Component Resource Manager with Provider Path: '{}'", target.getPath());
            dynamicComponentResourceManager.update(target.getPath());
            log.info("Update the Dynamic Component Resource Manager done");
//        } catch (LoginException e) {
//            log.error("Cannot Access Resource Resolver", e);
//        } catch (PersistenceException e) {
//            log.error("Failed to create Dynamic Component", e);
//        }
//        try (ResourceResolver resourceResolver = resourceResolverFactory.getAdministrativeResourceResolver(null)) {
            // Now test the setup
            Resource button = resourceResolver.getResource(WKND_COMPONENTS_FOLDER_PATH + "/" + "button");
            log.info("Static Button: '{}'", button);
            Resource button1 = resourceResolver.getResource(WKND_COMPONENTS_FOLDER_PATH + "/" + "button1");
            log.info("Dynamic Button 1: '{}'", button1);
            Resource container = resourceResolver.getResource(WKND_COMPONENTS_FOLDER_PATH + "/" + "container");
            log.info("Static Container: '{}'", container);
            Resource container1 = resourceResolver.getResource(WKND_COMPONENTS_FOLDER_PATH + "/" + "container1");
            log.info("Dynamic Container 1: '{}'", container1);
            Iterator<Resource> i = resourceResolver.listChildren(container1.getParent());
            int index = 0;
            while (i.hasNext()) {
                log.info("{}. Entry: '{}'", index++, i.next());
            }
        } catch (LoginException e) {
            log.error("2. Cannot Access Resource Resolver", e);
        } catch (PersistenceException e) {
            log.error("Failed to create Dynamic Component", e);
        }
    }

    private void addItemToListMap(Map<String, List<Property>> target, Property value) {
        String componentName = value.getComponent();
        List<Property> propertyList = target.get(componentName);
        if(propertyList == null) {
            propertyList = new ArrayList<>();
            target.put(componentName, propertyList);
        }
        propertyList.add(value);
    }

    public static final String EQUALS = "=";
    public static final String VERTICAL_LINE = "|";
    public static final char OPENING_MULTIPLE = '{';
    public static final char CLOSING_MULTIPLE = '}';
    public static final String MULTI_SEPARATOR = ";";

    private static class Property {
        private String component;
        private String name;
        private List<String> values = new ArrayList<>();

        public Property(String line, String messageTitle) {
            String[] split = line.split(EQUALS);
            if(split.length != 2) {
                throw new IllegalArgumentException(messageTitle + " is invalid (split on '" + EQUALS + "' does not yield 2 tokens): " + line);
            }
            String tempName = split[0];
            String tempValue = split[1];
            int index = tempValue.indexOf(VERTICAL_LINE);
            if( index > 0 && index < tempValue.length() - 1) {
                String[] splitTemp = tempValue.split("\\" + VERTICAL_LINE);
                this.component = tempName;
                this.name = splitTemp[0];
                tempValue = splitTemp[1];
                if(tempValue.charAt(0) == OPENING_MULTIPLE && tempValue.charAt(tempValue.length() - 1) == CLOSING_MULTIPLE) {
                    tempValue = tempValue.substring(1, tempValue.length() - 1);
                    splitTemp = tempValue.split(MULTI_SEPARATOR);
                    values.addAll(Arrays.asList(splitTemp));
                } else {
                    values.add(tempValue);
                }
            } else {
                this.name = tempName;
                if(tempValue.charAt(0) == OPENING_MULTIPLE && tempValue.charAt(tempValue.length() - 1) == CLOSING_MULTIPLE) {
                    tempValue = tempValue.substring(1, tempValue.length() - 1);
                    String[] splitTemp = tempValue.split(MULTI_SEPARATOR);
                    values.addAll(Arrays.asList(splitTemp));
                } else {
                    values.add(tempValue);
                }
            }
        }

//        public Property(String name, String value) {
//            this.name = name;
//            this.value = value;
//        }
//
//        public Property(String name, List<String> values) {
//            this.name = name;
//            this.values = values;
//        }

        public boolean isComponent() { return component != null; }
        public boolean isEmpty() { return values.isEmpty(); }
        public boolean isSingle() { return values.size() == 1; }

        public String getComponent() {
            return component;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return values.isEmpty() ? null : values.get(0);
        }

        public List<String> getValues() {
            return values;
        }

        @Override
        public String toString() {
            return "Property{" +
                "component-name='" + component + '\'' +
                ", name='" + name + '\'' +
                ", values=" + values +
                '}';
        }
    }
}