package org.apache.sling.dynamic;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.spi.resource.provider.ProviderContext;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import static org.apache.sling.api.resource.Resource.RESOURCE_TYPE_NON_EXISTING;
import static org.apache.sling.dynamic.DynamicComponent.createSyntheticFromResource;
import static org.osgi.framework.Constants.SERVICE_DESCRIPTION;
import static org.osgi.framework.Constants.SERVICE_VENDOR;

public class DynamicComponentResourceProviderService
    extends ResourceProvider
    implements DynamicComponentResourceProvider
{
    private final Logger log = LoggerFactory.getLogger(DynamicComponentResourceProviderService.class);

    @SuppressWarnings("rawtypes")
    private volatile ServiceRegistration serviceRegistration;

    private String sourceComponentRootPath;
    private String targetComponentRootPath;
    private boolean active;

    //---------- Service Registration

    public long registerService(Bundle bundle, String sourceComponentRootPath, String targetComponentRootPath) {
        this.sourceComponentRootPath = sourceComponentRootPath;
        this.targetComponentRootPath = targetComponentRootPath;
        log.info("Source Component Root Path: '{}', Target Component Root Path: '{}'", sourceComponentRootPath, targetComponentRootPath);

        final Dictionary<String, Object> props = new Hashtable<>();
        props.put("label", "Dynamic Component Resource Provider for: " + sourceComponentRootPath);
        props.put(SERVICE_DESCRIPTION, "Provides the Dynamic Component for " + sourceComponentRootPath + " resources as synthetic resources");
        props.put(SERVICE_VENDOR, "The Apache Software Foundation");
        props.put(ResourceProvider.PROPERTY_ROOT, targetComponentRootPath);
        props.put(getClass().getName(), bundle.getBundleId());

        log.info("Before Register RARPS with props: '{}'", props);
        serviceRegistration = bundle.getBundleContext().registerService(
            new String[] {ResourceProvider.class.getName(), DynamicComponentResourceProvider.class.getName()}, this, props
        );
        log.info("After Register RARPS, service registration: '{}'", serviceRegistration);
        active = true;
        return (Long) serviceRegistration.getReference().getProperty(Constants.SERVICE_ID);
    }

    public void unregisterService() {
        if (serviceRegistration != null) {
            try {
                serviceRegistration.unregister();
            } catch ( final IllegalStateException ise ) {
                // this might happen on shutdown, so ignore
            }
            serviceRegistration = null;
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String getComponentSourcePath() {
        return null;
    }

    @Override
    public String getDynamicComponentPath() {
        return null;
    }

    @Override
    public Resource getResource(ResolveContext ctx, String path, ResourceContext resourceContext, Resource parent) {
        log.info("Get Resource, path: '{}', parent: '{}'", path, parent);
        log.info("Get Resource, resolve context: '{}', resource context: '{}'", ctx, resourceContext);
        String resourcePath;
        if(path.startsWith("/")) {
            resourcePath = path;
        } else {
            resourcePath = parent.getPath() + "/" + path;
        }
        Resource answer = null;
        ResourceResolver resourceResolver = ctx.getResourceResolver();
        log.info("Resource Path: '{}'", resourcePath);
        Resource source = null;
        if(resourcePath.equals(targetComponentRootPath)) {
            source = resourceResolver.getResource(sourceComponentRootPath);
        } else if(resourcePath.startsWith(targetComponentRootPath)) {
            Resource sourceRoot = resourceResolver.getResource(sourceComponentRootPath);
            String componentName = resourcePath.substring(targetComponentRootPath.length() + 1);
            source = sourceRoot.getChild(componentName);
            log.info("Source from target component: '{}': '{}'", componentName, source);
            if (source == null || source.isResourceType(RESOURCE_TYPE_NON_EXISTING)) {
                source = null;
            }
        } else if(path.startsWith(sourceComponentRootPath)) {
            source = resourceResolver.getResource(path);
        }
        log.info("Source from path: '{}': '{}'", path, source);
        if (source != null && !source.isResourceType(RESOURCE_TYPE_NON_EXISTING)) {
            answer = createSyntheticFromResource(resourceResolver, source, sourceComponentRootPath);
        }
        log.info("Return resource: '{}'", answer);
        return answer;
    }

    @Override
    public Iterator<Resource> listChildren(ResolveContext ctx, Resource parent) {
        List<Resource> answer = null;
        log.info("List Children, resolve-context: '{}', parent: '{}'", ctx, parent);
        String resourcePath = parent.getPath();
        ResourceResolver resourceResolver = ctx.getResourceResolver();
        if(resourcePath.equals(targetComponentRootPath)) {
            resourcePath = sourceComponentRootPath;
        }
        Resource source = resourceResolver.getResource(resourcePath);
        if (source != null && !source.isResourceType(RESOURCE_TYPE_NON_EXISTING)) {
            answer = new ArrayList<>();
            Iterator<Resource> i = source.listChildren();
            while (i.hasNext()) {
                Resource item = i.next();
                answer.add(createSyntheticFromResource(resourceResolver, item, sourceComponentRootPath));
            }
        }
        return answer != null ? answer.iterator() : null;
    }

    @Override
    public void start(ProviderContext ctx) {
        log.info("Provider Start, context: '{}'", ctx);
        super.start(ctx);
    }

    @Override
    public void stop() {
        log.info("Provider Stop");
        super.stop();
    }
}
