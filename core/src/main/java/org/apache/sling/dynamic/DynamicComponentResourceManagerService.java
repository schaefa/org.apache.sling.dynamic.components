package org.apache.sling.dynamic;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * This class will register all available Tenant Resource
 * Types on startup as well as when a new Tenant is created
 */
@Component(
    service=DynamicComponentResourceManager.class,
    immediate = true
)
@Designate(ocd = DynamicComponentResourceManagerService.Configuration.class, factory = false)
public class DynamicComponentResourceManagerService
    implements DynamicComponentResourceManager
{
    public static final String DEFAULT_DYNAMIC_COMPONENT_SOURCE_PATH = "/apps/wknd/components";
    public static final String DEFAULT_DYNAMIC_COMPONENT_ROOT_PATH = "/apps/dynamic";
    public static final String DEFAULT_DYNAMIC_COMPONENT_PRIMARY_TYPE = "cq:Component";

    @ObjectClassDefinition(
        name = "Dynamic Component Resource Provider",
        description = "Configuration of the Dynamic Component Resource Provider")
    public @interface Configuration {
        @AttributeDefinition(
            name = "dynamic.component.source.path",
            description="Path of the Source of the Dynamic Components")
        String dynamic_component_source_path() default DEFAULT_DYNAMIC_COMPONENT_SOURCE_PATH;
        @AttributeDefinition(
            name = "dynamic.component.root.path",
            description="Path to the root of the Dynamic Components")
        String dynamic_component_root_path() default DEFAULT_DYNAMIC_COMPONENT_ROOT_PATH;
        @AttributeDefinition(
            name = "dynamic.component.primary.type",
            description="Primary Type of the Dynamic Components")
        String dynamic_component_primary_type() default DEFAULT_DYNAMIC_COMPONENT_PRIMARY_TYPE;
    }

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private Map<String, DynamicComponentResourceProvider> registeredServices = new HashMap<>();
    private BundleContext bundleContext;

    @Activate
    private void activate(BundleContext bundleContext, Configuration configuration) {
        log.info("Activate Started, bundle context: '{}'", bundleContext);
        this.bundleContext = bundleContext;
        try (ResourceResolver resourceResolver = resourceResolverFactory.getAdministrativeResourceResolver(null)) {
            Resource rootResource = resourceResolver.getResource(configuration.dynamic_component_source_path());
            log.info("Root Resource: '{}'", rootResource);
            // Look for all the Tenants in /content
            if(rootResource != null) {
                Iterator<Resource> i = rootResource.listChildren();
                boolean handleId = false;
                while(i.hasNext()) {
                    Resource contentChild = i.next();
                    log.info("Content Child Resource: '{}'", contentChild);
                    ValueMap childProperties = contentChild.getValueMap();
                    String primaryType = childProperties.get("jcr:primaryType", String.class);
                    String componentName = contentChild.getName();
                    log.info("Dynamic Child Source: '{}', Primary Type: '{}'", componentName, primaryType);
                    if(componentName != null && !componentName.isEmpty() && primaryType.equals(configuration.dynamic_component_primary_type())) {
                        handleId = true;
                        break;
                    }
                }
                if(handleId) {
                    DynamicComponentResourceProviderService service = new DynamicComponentResourceProviderService();
                    long id = service.registerService(bundleContext.getBundle(), rootResource.getPath(), configuration.dynamic_component_root_path());
                    log.info("After Registering Tenant RP: service: '{}', id: '{}'", service, id);
                    registeredServices.put(rootResource.getPath(), service);
                }
            }
            log.info("Done setting up Dynamic Components -> test it");
            Resource test = resourceResolver.getResource(configuration.dynamic_component_root_path());
            log.info("Root DCL '{}'", test);
            test = resourceResolver.getResource(configuration.dynamic_component_root_path() + "/accordion");
            log.info("Accordion DCL '{}'", test);
        } catch (LoginException e) {
            log.error("Was not able to obtain Service Resource Resolver", e);
        }
    }

    @Deactivate
    private void deactivate() {
        for(DynamicComponentResourceProvider service: registeredServices.values()) {
            log.info("Before UnRegistering Tenant RP, service: '{}'", service);
            service.unregisterService();
            log.info("After UnRegistering Tenant RP, service: '{}'", service);
        }
    }

//    @Override
//    public void registerTenant(String tenantName, String themeName) {
//        if(isEmpty(tenantName)) {
//            throw new IllegalArgumentException("Tenant Name must be specified");
//        }
//        if(isEmpty(themeName)) {
//            throw new IllegalArgumentException("Theme Name must be specified");
//        }
//        TenantAppsResourceProviderService service = new TenantAppsResourceProviderService();
//        service.registerService(bundleContext.getBundle(), tenantName, themeName);
//        registeredServices.put(tenantName, service);
//    }
//
//    @Override
//    public void registerTenant(Resource tenantRoot) {
//        if(tenantRoot != null) {
//            ValueMap childProperties = tenantRoot.getValueMap();
//            String source = childProperties.get(SOURCE_SITE, String.class);
//            String primaryType = childProperties.get(JCR_PRIMARY_TYPE, String.class);
//            String tenantName = tenantRoot.getName();
//            if(primaryType.equals(SITE_PRIMARY_TYPE)) {
//                registerTenant(tenantName, source);
//            } else {
//                throw new IllegalArgumentException("Resource: '" + tenantRoot + "' is not a Peregrine Site");
//            }
//        }
//    }
//
//    @Override
//    public void unregisterTenant(String tenantName) {
//        TenantAppsResourceProviderService service = registeredServices.get(tenantName);
//        if(service != null) {
//            service.unregisterService();
//        }
//    }
//
//    @Override
//    public void unregisterTenant(Resource tenantRoot) {
//        unregisterTenant(tenantRoot.getName());
//    }
//
//    @Override
//    public List<String> getListOfTenants() {
//        return new ArrayList<>(registeredServices.keySet());
//    }
}

