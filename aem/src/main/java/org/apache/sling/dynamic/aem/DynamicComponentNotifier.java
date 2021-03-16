package org.apache.sling.dynamic.aem;

//import com.day.cq.wcm.api.components.ComponentManager;
//import org.apache.sling.api.resource.LoginException;
//import org.apache.sling.api.resource.Resource;
//import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.dynamic.common.DynamicComponent;
import org.apache.sling.dynamic.common.DynamicComponentFilterNotifier;
//import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
//import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
//import org.osgi.service.component.annotations.ReferenceCardinality;
//import org.osgi.service.component.annotations.ReferencePolicy;
//import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
//import javax.jcr.observation.EventListener;
//import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
//import java.util.HashMap;
import java.util.Map;
//import java.util.concurrent.atomic.AtomicBoolean;

import static javax.jcr.observation.Event.NODE_ADDED;
import static javax.jcr.observation.Event.NODE_REMOVED;
//import static org.apache.sling.dynamic.common.Constants.DYNAMIC_COMPONENTS_SERVICE_USER;

//@Component(
//    name = "Dynamic Component Notifier",
//    immediate = true,
//    property = {
//        Constants.SERVICE_DESCRIPTION + "=" + "Sends Events to the Component Cache to inform about Dynamic Components"
//    }
//)
public class DynamicComponentNotifier
    implements DynamicComponentFilterNotifier
{
    public static final Logger LOGGER = LoggerFactory.getLogger(DynamicComponentNotifier.class);

    private Object eventListener;
    private Method onEvent;

    public DynamicComponentNotifier() {
        LOGGER.info("DC Notifier created");
    }

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Activate
    public void activate() {
//        try (ResourceResolver resourceResolver = resourceResolverFactory.getServiceResourceResolver(
//            new HashMap<String, Object>() {{ put(ResourceResolverFactory.SUBSERVICE, DYNAMIC_COMPONENTS_SERVICE_USER); }}
//        )) {
//            ComponentManager componentManager = resourceResolver.adaptTo(ComponentManager.class);
//            LOGGER.info("Got Component Manager: '{}'", componentManager);
//            if(componentManager != null) {
//                Field componentCacheField = componentManager.getClass().getDeclaredField("cache");
//                componentCacheField.setAccessible(true);
//                eventListener = componentCacheField.get(componentManager);
//                Class eventListenerClass = EventListener.class;
//                onEvent = eventListenerClass.getDeclaredMethod("onEvent", EventIterator.class);
//                this.eventListener = eventListener;
//                LOGGER.info("onEvent() method: '{}', component cache: '{}'", onEvent, eventListener);
//            }
//        } catch (LoginException e) {
//            LOGGER.error("Failed to obtain Service Resource Resolver", e);
//        } catch (NoSuchFieldException e) {
//            LOGGER.error("Could not find Component Cache Field", e);
//        } catch (IllegalAccessException e) {
//            LOGGER.error("Could not get Component Cache", e);
//        } catch (NoSuchMethodException e) {
//            LOGGER.error("Could not get On Event Method", e);
//        }
    }

    @Override
    public void addDynamicComponent(String dynamicComponentPath, Resource source) {
        if(onEvent != null) {
            sendEvent(dynamicComponentPath, true);
        }
    }

    @Override
    public void removeDynamicComponent(String dynamicComponentPath) {
        sendEvent(dynamicComponentPath, false);
    }

    private void sendEvent(String path, boolean added) {
        Event event = new EventWrapper(added ? NODE_ADDED : NODE_REMOVED, path);
        EventIterator eventIterator = new EventIteratorWrapper(event);
        try {
            LOGGER.info("Send Event, added: '{}', path: '{}'", added, path);
            onEvent.invoke(
                eventListener,
                eventIterator
            );
        } catch (IllegalAccessException e) {
            LOGGER.error("Failed to send Event due to access restrictions, " + (added ? " addded" : "removed") + " path: '" + path + "'", e);
        } catch (InvocationTargetException e) {
            LOGGER.error("Failed to send Event due to invocation failure, " + (added ? " addded" : "removed") + " path: '" + path + "'", e);
        }
    }

    private static class EventIteratorWrapper implements EventIterator {
        private Event event;
        private boolean handled;

        public EventIteratorWrapper(Event event) {
            this.event = event;
            handled = false;
        }

        @Override
        public Event nextEvent() {
            if(!handled) { handled = true; return event; } else { return null; }
        }

        @Override
        public void skip(long skipNum) {}

        @Override
        public long getSize() { return 1; }

        @Override
        public long getPosition() { return handled ? 0: 1; }

        @Override
        public boolean hasNext() { return handled ? false: true; }

        @Override
        public Object next() { return nextEvent(); }

        @Override
        public void remove() {}
    }

    private static class EventWrapper implements Event {
        int type;
        String path;
        public EventWrapper(int type, String path) {
            this.path = path;
            this.type = type;
        }

        @Override
        public int getType() { return type; }

        @Override
        public String getPath() throws RepositoryException { return path; }

        @Override
        public String getUserID() { return null; }

        @Override
        public String getIdentifier() throws RepositoryException { return null; }

        @Override
        public Map getInfo() throws RepositoryException { return null; }

        @Override
        public String getUserData() throws RepositoryException { return null; }

        @Override
        public long getDate() throws RepositoryException { return 0; }
    }
}