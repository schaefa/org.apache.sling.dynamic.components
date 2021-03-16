package org.apache.sling.dynamic.aem;

import com.day.text.Text;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.io.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static org.apache.sling.dynamic.common.Constants.REFERENCE_PROPERTY_NAME;
import static org.apache.sling.dynamic.common.Constants.SLING_RESOURCE_SUPER_TYPE_PROPERTY;

public class DynamicComponentMapper {

    public static final Logger LOGGER = LoggerFactory.getLogger(DynamicComponentMapper.class);

    public static String[] searchPaths = new String[] {};

    private String targetPath;
    private Resource source;
    private PropertyHierarchy propertyHierarchy;

    public DynamicComponentMapper(String targetPath, Resource source) {
        this.targetPath = targetPath;
        this.source = source;
        this.propertyHierarchy = new PropertyHierarchy(source);
        searchPaths = source.getResourceResolver().getSearchPath();
    }

    public String mapToJSon() {
        String component = "";
        int index = targetPath.indexOf('/', 1);
        if(index > 0) {
            try {
                StringWriter writer = new StringWriter();
                JSONWriter jsonWriter = new JSONWriter(writer);
                String resourceType = propertyHierarchy.get("jcr:resourceType");
                if(resourceType == null) {
                    resourceType = targetPath.substring(index + 1);
                }
                jsonWriter.object();
                writeComponentJson(jsonWriter, resourceType, true, "");
                jsonWriter.endObject();
//            component = "\"" + targetPath.substring(index + 1) + "\": {"
//                + "\"path\":\"" + targetPath + "\"";
//            if (propertyHierarchy.containsKey("componentGroup")) {
//                component += ",\"group\":\"" + propertyHierarchy.get("componentGroup", "weird-group") + "\"";
//            }
//            if (propertyHierarchy.containsKey("jcr:title")) {
//                component += ",\"title\":\"" + propertyHierarchy.get("jcr:title", "weird-title") + "\"";
//            }
//            component += ",\"resourceType\":\"" + targetPath.substring(index + 1) + "\"";
//            if (propertyHierarchy.containsKey("cq:icon")) {
//                component += ",\"iconName\":\"" + propertyHierarchy.get("cq:icon", "weird-icon") + "\"";
//            }
//            String children = handleChildren(propertyHierarchy);
//            if (!children.isEmpty()) {
//                component += "," + children;
//            }
//            component += "}";
                component = writer.toString();
            } catch (JSONException e) {
                LOGGER.error("Failed to Map Component: '{}'", targetPath);
                LOGGER.error("Failed to Map Component to JSon", e);
            }
        } else {
            LOGGER.warn("Dynamic Path is not valid: '{}' -> ignored", targetPath);
        }
        return component;
    }

//    private String handleChildren(PropertyHierarchy parent) {
//        String answer = "";
//        boolean firstOuter = true;
//        for(PropertyHierarchy child: parent.children) {
//            if(firstOuter) { firstOuter = false; } else { answer += ","; }
//            LOGGER.info("Handle Child: '{}'", child);
//            answer += "\"" + child.providedComponentName + "\": {";
//            boolean first = true;
//            for(Entry<String,Object> entry: child.hierarchicalProperties.entrySet()) {
//                if(first) { first = false; } else { answer += ","; }
//                answer += "\"" + entry.getKey() + "\": \"" + entry.getValue() + "\"";
//            }
//            String children = handleChildren(child);
//            if(!children.isEmpty()) {
//                answer += "," + children;
//            }
//            answer += "}";
//        }
//        return answer;
//    }

    private void writeComponentJson(JSONWriter w, String key, boolean wrapWithKey, String prefix) throws JSONException {
        try {
            if (wrapWithKey) {
                w.key(key).object();
            }
            w.key("path").value(targetPath);
            writeKeyValueIfPresent(w, "group", "componentGroup");
            writeKeyValueOrElse(w, "title", "jcr:title", Text.getName(targetPath));
            writeKeyValueOrElse(w, "resourceType", "jcr:resourceType", key);
            this.writeDivAttributes(w);
            writeKeyValueIfPresent(w, "description", "jcr:description");
            PropertyHierarchy child = propertyHierarchy.children.get("thumbnail.png");
            if(child != null) {
                writeKeyValue(w, "thumbnail", targetPath + '/' + "thumbnail.png");
            }
            child = propertyHierarchy.children.get("icon.png");
            if(child != null) {
                writeKeyValue(w, "icon", targetPath + '/' + "icon.png");
            }

//AS Ignore for now
//            ComponentEditConfig ed = c.getEditConfig();
//            if (ed != null && !ed.isDefault()) {
//                w.key("config");
//                ed.write(w);
//            }

            String dialogPath = null;
            child = propertyHierarchy.children.get("dialog");
            if(child == null) {
                propertyHierarchy.get("dialogPath");
            } else {
                dialogPath = targetPath + '/' + "dialog";
            }
            if (dialogPath != null) {
                if (!dialogPath.endsWith(".json")) {
                    dialogPath = dialogPath + ".infinity.json";
                }
                w.key("dialog").value(dialogPath);
            }
            String templatePath = propertyHierarchy.get("cq:templatePath");
            if(templatePath == null) {
                child = propertyHierarchy.children.get("cq:template");
                templatePath = child == null ? null : targetPath + '/' + "cq:template";
            }
            if (templatePath != null) {
                w.key("templatePath").value(templatePath);
            }
            w.key("cellNames");
            w.array();
            String cellName = propertyHierarchy.get("cq:cellName");
            w.value(cellName == null ? Text.getName(targetPath) : cellName);
            for(String name: propertyHierarchy.hierarchyNames) {
                w.value(name);
            }
            w.endArray();
            child = propertyHierarchy.children.get("cq:childEditConfig");
            String isContainer = propertyHierarchy.get("cq:isContainer");
            w.key("isContainer").value(Boolean.parseBoolean(isContainer) || child != null);
            if (wrapWithKey) {
                w.endObject();
            }
        } catch (JSONException e) {
            LOGGER.error("Unable to write JSON output for component {}", targetPath, e);
        }

    }

    private void writeKeyValue(JSONWriter w, String name, String propertyName) throws JSONException {
        w.key(name).value(propertyHierarchy.get(propertyName));
    }

    private void writeKeyValueIfPresent(JSONWriter w, String name, String propertyName) throws JSONException {
        String value = propertyHierarchy.get(propertyName);
        if (value != null && !value.isEmpty()) {
            w.key(name).value(value);
        }
    }

    private void writeKeyValueOrElse(JSONWriter w, String name, String propertyName, String fallback) throws JSONException {
        String value = propertyHierarchy.get(propertyName);
        if (value == null) {
            value = fallback;
        }
        w.key(name).value(value);
    }

    private void writeDivAttributes(JSONWriter w) throws JSONException {
        Map<String,String> attributes = new HashMap<>();
        PropertyHierarchy child = propertyHierarchy.children.get("cq:htmlTag");
        if(child != null) {
            for(Entry<String, Object> property: child.hierarchicalProperties.entrySet()) {
                String name = property.getKey();
                if(name != null && (name.indexOf(':') < 0 || name.equals("cq:tagName"))) {
                    attributes.put(name, property.getValue() + "");
                }
            }
        }

        if (!attributes.containsKey("class")) {
            StringBuilder clazz = new StringBuilder();
            String delim = "";
            String cellName = propertyHierarchy.get("cq:cellName");
            if(cellName == null || cellName.isEmpty()) {
                cellName = Text.getName(targetPath);
                delim = " ";
                clazz.append(delim);
                clazz.append(cellName);
                for(String name: propertyHierarchy.hierarchyNames) {
                    clazz.append(delim);
                    clazz.append(name);
                }
            } else {
                clazz.append(delim);
                clazz.append(cellName);
//                delim = " ";
            }
            if (clazz.length() > 0) {
                attributes.put("class", clazz.toString());
            }
        }

        if(!attributes.isEmpty()) {
            w.key("divAttributes").object();
            for(Entry<String,String> entry: attributes.entrySet()) {
                w.key(entry.getKey()).value(entry.getValue());
            }
            w.endObject();
        }
    }

    private static class PropertyHierarchy {
        private String providedComponentPath;
        private String providedComponentName;
        private List<String> hierarchyNames = new ArrayList<>();
        private Map<String, PropertyHierarchy> children = new HashMap<>();
        private Map<String, Object> hierarchicalProperties = new HashMap<>();

        public PropertyHierarchy(Resource source) {
            providedComponentPath = source.getPath();
            providedComponentName = source.getName();
            LOGGER.info("Search Paths: '{}'", Arrays.asList(searchPaths));
            traverse(source, searchPaths);
            for(Resource child: source.getChildren()) {
                Resource reference = followReferences(child);
                LOGGER.info("Source: '{}', Child: '{}', resulting Reference: '{}'", source, child, reference);
                if(reference != null) {
                    children.put(child.getName(), new PropertyHierarchy(reference));
                }
            }
        }

        private Resource followReferences(Resource resource) {
            Resource answer = resource;
            ValueMap properties = resource.getValueMap();
            String referencePath = properties.get(REFERENCE_PROPERTY_NAME, String.class);
            LOGGER.info("Component Path: '{}', Reference Path: '{}'", providedComponentPath, referencePath);
            if(referencePath != null) {
                Resource temp = resource.getResourceResolver().getResource(referencePath);
                LOGGER.info("Reference Resource: '{}'", temp);
                if(temp != null) {
                    answer = followReferences(temp);
                } else {
                    answer = null;
                }
            }
            return answer;
        }

        public String getProvidedComponentPath() {
            return providedComponentPath;
        }

        public boolean containsKey(String propertyName) {
            return hierarchicalProperties.containsKey(propertyName);
        }

        public String get(String propertyName) {
            if(hierarchicalProperties.containsKey(propertyName)) {
                Object temp = hierarchicalProperties.get(propertyName);
                return temp == null ? "" : temp.toString();
            } else {
                return null;
            }
        }

        public String get(String propertyName, String defaultValue) {
            if(hierarchicalProperties.containsKey(propertyName)) {
                Object temp = hierarchicalProperties.get(propertyName);
                return temp == null ? "" : temp.toString();
            } else {
                return defaultValue;
            }
        }

        private void traverse(Resource resource, String[] searchPaths) {
            LOGGER.info("Traverse resource: '{}'", resource);
            ValueMap properties = resource.getValueMap();
            if(properties != null) {
                for(Entry<String,Object> entry: properties.entrySet()) {
                    if(!hierarchicalProperties.containsKey(entry.getKey())) {
                        hierarchicalProperties.put(entry.getKey(), entry.getValue());
                    }
                }
                String superType = properties.get(SLING_RESOURCE_SUPER_TYPE_PROPERTY, String.class);
                if(superType != null) {
                    LOGGER.info("Super Type found: '{}'", superType);
                    Resource superResource = null;
                    if(!superType.startsWith("/")) {
                        for(String searchPath: searchPaths) {
                            String path = searchPath + superType;
                            superResource = resource.getResourceResolver().getResource(path);
                            if(superResource != null) {
                                LOGGER.info("Found Super Type ('{}'): '{}'", path, superResource);
                                break;
                            }
                        }
                    } else {
                        superResource = resource.getResourceResolver().getResource(superType);
                    }
                    if(superResource != null) {
                        LOGGER.info("Super Resource Name: '{}'", superResource.getName());
                        hierarchyNames.add(superResource.getName());
                        traverse(superResource, searchPaths);
                    }
                }
            }
        }
    }

}
