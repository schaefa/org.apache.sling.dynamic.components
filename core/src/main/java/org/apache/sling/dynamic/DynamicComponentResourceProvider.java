package org.apache.sling.dynamic;

import org.osgi.framework.Bundle;

public interface DynamicComponentResourceProvider {
    long registerService(Bundle bundle, String sourceComponentRootPath, String targetComponentRootPath);
    void unregisterService();

    boolean isActive();
    String getComponentSourcePath();
    String getDynamicComponentPath();
}
