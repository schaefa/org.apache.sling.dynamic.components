package org.apache.sling.dynamic.common;

import org.apache.sling.api.resource.Resource;

public interface DynamicComponentFilterNotifier {
    void addDynamicComponent(String dynamicComponentPath, Resource providedComponent);
    void removeDynamicComponent(String dynamicComponentPath);
}
