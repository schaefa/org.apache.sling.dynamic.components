package org.apache.sling.dynamic.core;

import org.osgi.framework.Bundle;

import java.util.List;

public interface DynamicComponentResourceProvider {

    long registerService(Bundle bundle, String targetRootPath, String providerRootPath);

    void unregisterService();

    boolean isActive();
    String getTargetRootPath();
    List<String> getProvidedComponentPaths();
}
