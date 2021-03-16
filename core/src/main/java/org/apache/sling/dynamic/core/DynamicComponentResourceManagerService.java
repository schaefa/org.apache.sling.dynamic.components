package org.apache.sling.dynamic.core;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.dynamic.common.DynamicComponentFilterNotifier;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.apache.sling.dynamic.common.Constants.DYNAMIC_COMPONENTS_SERVICE_USER;
import static org.apache.sling.dynamic.common.Constants.JCR_PRIMARY_TYPE;
import static org.apache.sling.dynamic.common.Constants.REP_POLICY;

/**
 * This class will register all available Tenant Resource
 * Types on startup as well as when a new Tenant is created
 */
@Component(
    service=DynamicComponentResourceManager.class,
    immediate = true,
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
@Designate(ocd = DynamicComponentResourceManagerService.Configuration.class, factory = true)
public class DynamicComponentResourceManagerService
    implements DynamicComponentResourceManager
{
    @ObjectClassDefinition(
        name = "Dynamic Component Resource Provider",
        description = "Configuration of the Dynamic Component Resource Provider")
    public @interface Configuration {
        @AttributeDefinition(
            name = "Dynamic Component Target Path",
            description="Path to the Folder where the Dynamic Components will added to dynamically")
        String dynamic_component_target_path();
    }

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private DynamicComponentFilterNotifier dynamicComponentFilterNotifier;

    // Make sure that the Service User Mapping is available before obtaining the Service Resource Resolver
    @Reference(policyOption= ReferencePolicyOption.GREEDY)
    private ServiceUserMapped serviceUserMapped;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private Map<String, DynamicComponentResourceProvider> registeredServices = new HashMap<>();
    private BundleContext bundleContext;
    private String dynamicTargetPath;

    @Activate
    private void activate(BundleContext bundleContext, Configuration configuration) {
        log.info("Activate Started, bundle context: '{}'", bundleContext);
        this.bundleContext = bundleContext;
        dynamicTargetPath = configuration.dynamic_component_target_path();
        log.info("Dynamic Target Path: '{}'", dynamicTargetPath);
    }

    public void update(String dynamicProviderPath) {
        try (ResourceResolver resourceResolver = resourceResolverFactory.getServiceResourceResolver(
            new HashMap<String, Object>() {{ put(ResourceResolverFactory.SUBSERVICE, DYNAMIC_COMPONENTS_SERVICE_USER); }}
        )) {
            Resource dynamicProvider = resourceResolver.getResource(dynamicProviderPath);
            Resource dynamicTarget = resourceResolver.getResource(dynamicTargetPath);
            log.info("Dynamic Resource Provider: '{}', Target: '{}'", dynamicProvider, dynamicTarget);
            // Look for all the Tenants in /content
            if(dynamicProvider != null) {
                DynamicComponentResourceProviderHandler service = new DynamicComponentResourceProviderHandler();
                log.info("Dynamic Target: '{}', Dynamic Provider: '{}'", dynamicTarget, dynamicProvider);
                long id = service.registerService(bundleContext.getBundle(), dynamicTargetPath, dynamicProviderPath);
                log.info("After Registering Tenant RP: service: '{}', id: '{}'", service, id);
                registeredServices.put(dynamicTarget.getPath(), service);
            }
            Iterator<Resource> i = dynamicProvider.listChildren();
            while(i.hasNext()) {
                Resource provided = i.next();
                String componentName = provided.getName();
                if(componentName.equals(REP_POLICY)) {
                    continue;
                }
                log.info("Provided Dynamic: '{}'", provided);
                ValueMap childProperties = provided.getValueMap();
                String primaryType = childProperties.get(JCR_PRIMARY_TYPE, String.class);
                log.info("Dynamic Child Source: '{}', Primary Type: '{}'", componentName, primaryType);
                if(componentName != null && !componentName.isEmpty()) {
                    dynamicComponentFilterNotifier.addDynamicComponent(
                        dynamicTargetPath + '/' + componentName, provided
                    );
                }
            }
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
}

