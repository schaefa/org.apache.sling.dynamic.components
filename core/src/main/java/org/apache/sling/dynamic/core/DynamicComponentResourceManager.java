package org.apache.sling.dynamic.core;

public interface DynamicComponentResourceManager {

    /**
     * Whenever a Dynamic Provider Folder is ready to be handled
     * this method is called to create the Dynamic Components
     *
     * @param dynamicProviderPath Path to the Folder where the dynamic components are located in
     */
    void update(String dynamicProviderPath);
}
