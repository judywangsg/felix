/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.framework;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.apache.felix.framework.capabilityset.CapabilitySet;
import org.apache.felix.framework.capabilityset.SimpleFilter;
import org.apache.felix.framework.wiring.BundleCapabilityImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

public class ServiceRegistry
{
    private final Logger m_logger;
    private long m_currentServiceId = 1L;
    // Maps bundle to an array of service registrations.
    private final Map<Bundle, ServiceRegistration<?>[]> m_regsMap = Collections.synchronizedMap(new HashMap<Bundle, ServiceRegistration<?>[]>());
    // Capability set for all service registrations.
    private final CapabilitySet m_regCapSet;

    // Maps registration to thread to keep track when a
    // registration is in use, which will cause other
    // threads to wait.
    private final Map<ServiceRegistration<?>, Object> m_lockedRegsMap = new HashMap<ServiceRegistration<?>, Object>();
    // Maps bundle to an array of usage counts.
    private final Map<Bundle, UsageCount[]> m_inUseMap = new HashMap<Bundle, UsageCount[]>();

    private final ServiceRegistryCallbacks m_callbacks;

    private final WeakHashMap<ServiceReference<?>, ServiceReference<?>> m_blackList =
        new WeakHashMap<ServiceReference<?>, ServiceReference<?>>();

    private final static Class<?>[] m_hookClasses = {
        org.osgi.framework.hooks.bundle.CollisionHook.class,
        org.osgi.framework.hooks.bundle.FindHook.class,
        org.osgi.framework.hooks.bundle.EventHook.class,
        org.osgi.framework.hooks.service.EventHook.class,
        org.osgi.framework.hooks.service.EventListenerHook.class,
        org.osgi.framework.hooks.service.FindHook.class,
        org.osgi.framework.hooks.service.ListenerHook.class,
        org.osgi.framework.hooks.weaving.WeavingHook.class,
        org.osgi.framework.hooks.weaving.WovenClassListener.class,
        org.osgi.framework.hooks.resolver.ResolverHookFactory.class,
        org.osgi.service.url.URLStreamHandlerService.class,
        java.net.ContentHandler.class
    };
    private final Map<Class<?>, Set<ServiceReference<?>>> m_allHooks =
        new HashMap<Class<?>, Set<ServiceReference<?>>>();

    public ServiceRegistry(Logger logger, ServiceRegistryCallbacks callbacks)
    {
        m_logger = logger;
        m_callbacks = callbacks;

        List indices = new ArrayList();
        indices.add(Constants.OBJECTCLASS);
        m_regCapSet = new CapabilitySet(indices, false);
    }

    public ServiceReference<?>[] getRegisteredServices(Bundle bundle)
    {
        ServiceRegistration<?>[] regs = m_regsMap.get(bundle);
        if (regs != null)
        {
            List<Object> refs = new ArrayList<Object>(regs.length);
            for (int i = 0; i < regs.length; i++)
            {
                try
                {
                    refs.add(regs[i].getReference());
                }
                catch (IllegalStateException ex)
                {
                    // Don't include the reference as it is not valid anymore
                }
            }
            return refs.toArray(new ServiceReference[refs.size()]);
        }
        return null;
    }

    // Caller is expected to fire REGISTERED event.
    public ServiceRegistration<?> registerService(
        BundleContext context, String[] classNames, Object svcObj, Dictionary dict)
    {
        ServiceRegistrationImpl reg = null;

        synchronized (this)
        {
            Bundle bundle = context.getBundle();

            // Create the service registration.
            reg = new ServiceRegistrationImpl(
                this, bundle, classNames, new Long(m_currentServiceId++), svcObj, dict);

            // Keep track of registered hooks.
            addHooks(classNames, svcObj, reg.getReference());

            // Get the bundles current registered services.
            ServiceRegistration<?>[] regs = m_regsMap.get(bundle);
            m_regsMap.put(bundle, addServiceRegistration(regs, reg));
            m_regCapSet.addCapability((BundleCapabilityImpl) reg.getReference());
        }

        return reg;
    }

    public void unregisterService(Bundle bundle, ServiceRegistration<?> reg)
    {
        // If this is a hook, it should be removed.
        removeHook(reg.getReference());

        synchronized (this)
        {
            // Note that we don't lock the service registration here using
            // the m_lockedRegsMap because we want to allow bundles to get
            // the service during the unregistration process. However, since
            // we do remove the registration from the service registry, no
            // new bundles will be able to look up the service.

            // Now remove the registered service.
            ServiceRegistration<?>[] regs = m_regsMap.get(bundle);
            m_regsMap.put(bundle, removeServiceRegistration(regs, reg));
            m_regCapSet.removeCapability((BundleCapabilityImpl) reg.getReference());
        }

        // Notify callback objects about unregistering service.
        if (m_callbacks != null)
        {
            m_callbacks.serviceChanged(
                new ServiceEvent(ServiceEvent.UNREGISTERING, reg.getReference()), null);
        }

        // Now forcibly unget the service object for all stubborn clients.
        ServiceReference<?> ref = reg.getReference();
        Bundle[] clients = getUsingBundles(ref);
        for (int i = 0; (clients != null) && (i < clients.length); i++)
        {
            UsageCount[] usages = m_inUseMap.get(clients[i]);
            for (int x = 0; (usages != null) && (x < usages.length); x++)
            {
                if (usages[x].m_ref.equals(ref))
                {
                	ungetService(clients[i], ref, (usages[x].m_prototype ? usages[x].m_svcObj : null));
                }
            }
        }
        // Invalidate registration
        ((ServiceRegistrationImpl) reg).invalidate();
        // Bundles are allowed to get a reference while unregistering
        for (int i = 0; (clients != null) && (i < clients.length); i++)
        {
            UsageCount[] usages = m_inUseMap.get(clients[i]);
            for (int x = 0; (usages != null) && (x < usages.length); x++)
            {
                if (usages[x].m_ref.equals(ref))
                {
                	ungetService(clients[i], ref, (usages[x].m_prototype ? usages[x].m_svcObj : null));
                }
            }
        }
    }

    /**
     * This method retrieves all services registrations for the specified
     * bundle and invokes <tt>ServiceRegistration.unregister()</tt> on each
     * one. This method is only called be the framework to clean up after
     * a stopped bundle.
     * @param bundle the bundle whose services should be unregistered.
    **/
    public void unregisterServices(Bundle bundle)
    {
        // Simply remove all service registrations for the bundle.
        ServiceRegistration<?>[] regs = null;
        synchronized (this)
        {
            regs = m_regsMap.get(bundle);
        }

        // Note, there is no race condition here with respect to the
        // bundle registering more services, because its bundle context
        // has already been invalidated by this point, so it would not
        // be able to register more services.

        // Unregister each service.
        for (int i = 0; (regs != null) && (i < regs.length); i++)
        {
            if (((ServiceRegistrationImpl) regs[i]).isValid())
            {
                try
                {
                    regs[i].unregister();
                }
                catch (IllegalStateException e)
                {
                    // Ignore exception if the service has already been unregistered
                }
            }
        }

        // Now remove the bundle itself.
        synchronized (this)
        {
            m_regsMap.remove(bundle);
        }
    }

    public synchronized Collection getServiceReferences(String className, SimpleFilter filter)
    {
        if ((className == null) && (filter == null))
        {
            // Return all services.
            filter = new SimpleFilter(null, null, SimpleFilter.MATCH_ALL);
        }
        else if ((className != null) && (filter == null))
        {
            // Return services matching the class name.
            filter = new SimpleFilter(Constants.OBJECTCLASS, className, SimpleFilter.EQ);
        }
        else if ((className != null) && (filter != null))
        {
            // Return services matching the class name and filter.
            List<SimpleFilter> filters = new ArrayList<SimpleFilter>(2);
            filters.add(new SimpleFilter(Constants.OBJECTCLASS, className, SimpleFilter.EQ));
            filters.add(filter);
            filter = new SimpleFilter(null, filters, SimpleFilter.AND);
        }
        // else just use the specified filter.

        return m_regCapSet.match(filter, false);
    }

    public synchronized ServiceReference<?>[] getServicesInUse(Bundle bundle)
    {
        UsageCount[] usages = m_inUseMap.get(bundle);
        if (usages != null)
        {
            ServiceReference<?>[] refs = new ServiceReference[usages.length];
            for (int i = 0; i < refs.length; i++)
            {
                refs[i] = usages[i].m_ref;
            }
            return refs;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <S> S getService(final Bundle bundle, final ServiceReference<S> ref, final boolean isServiceObjects)
    {
    	// prototype scope is only possible if called from ServiceObjects
    	final boolean isPrototype = isServiceObjects && ref.getProperty(Constants.SERVICE_SCOPE) == Constants.SCOPE_PROTOTYPE;
        UsageCount usage = null;
        Object svcObj = null;

        // Get the service registration.
        final ServiceRegistrationImpl reg =
            ((ServiceRegistrationImpl.ServiceReferenceImpl) ref).getRegistration();

        synchronized (this)
        {
            // First make sure that no existing operation is currently
            // being performed by another thread on the service registration.
            for (Object o = m_lockedRegsMap.get(reg); (o != null); o = m_lockedRegsMap.get(reg))
            {
                // We don't allow cycles when we call out to the service factory.
                if (o.equals(Thread.currentThread()))
                {
                    throw new ServiceException(
                        "ServiceFactory.getService() resulted in a cycle.",
                        ServiceException.FACTORY_ERROR,
                        null);
                }

                // Otherwise, wait for it to be freed.
                try
                {
                    wait();
                }
                catch (InterruptedException ex)
                {
                	Thread.currentThread().interrupt();
                }
            }

            // Lock the service registration.
            m_lockedRegsMap.put(reg, Thread.currentThread());

            // Make sure the service registration is still valid.
            if (reg.isValid())
            {
                // Get the usage count, if any.
                // if prototype, we always create a new usage
                usage = isPrototype ? null : getUsageCount(bundle, ref, null);

                // If we don't have a usage count, then create one and
                // since the spec says we increment usage count before
                // actually getting the service object.
                if (usage == null)
                {
                    usage = addUsageCount(bundle, ref, isPrototype);
                }

                // Increment the usage count and grab the already retrieved
                // service object, if one exists.
                usage.m_count++;
                svcObj = usage.m_svcObj;
                if ( isServiceObjects )
                {
                	usage.m_serviceObjectsCount++;
                }
            }
        }

        // If we have a usage count, but no service object, then we haven't
        // cached the service object yet, so we need to create one now without
        // holding the lock, since we will potentially call out to a service
        // factory.
        try
        {
            if ((usage != null) && (svcObj == null))
            {
                svcObj = reg.getService(bundle);
            }
        }
        finally
        {
            // If we successfully retrieved a service object, then we should
            // cache it in the usage count. If not, we should flush the usage
            // count. Either way, we need to unlock the service registration
            // so that any threads waiting for it can continue.
            synchronized (this)
            {
                // Before caching the service object, double check to see if
                // the registration is still valid, since it may have been
                // unregistered while we didn't hold the lock.
                if (!reg.isValid() || (svcObj == null))
                {
                    flushUsageCount(bundle, ref, usage);
                }
                else
                {
                    usage.m_svcObj = svcObj;
                }
                m_lockedRegsMap.remove(reg);
                notifyAll();
            }
        }

        return (S) svcObj;
    }

    public boolean ungetService(Bundle bundle, ServiceReference<?> ref, Object svcObj)
    {
    	// prototype scope is only possible if called from ServiceObjects
    	final boolean isPrototype = svcObj != null && ref.getProperty(Constants.SERVICE_SCOPE) == Constants.SCOPE_PROTOTYPE;

    	UsageCount usage = null;
        ServiceRegistrationImpl reg =
            ((ServiceRegistrationImpl.ServiceReferenceImpl) ref).getRegistration();

        synchronized (this)
        {
            // First make sure that no existing operation is currently
            // being performed by another thread on the service registration.
            for (Object o = m_lockedRegsMap.get(reg); (o != null); o = m_lockedRegsMap.get(reg))
            {
                // We don't allow cycles when we call out to the service factory.
                if (o.equals(Thread.currentThread()))
                {
                    throw new IllegalStateException(
                        "ServiceFactory.ungetService() resulted in a cycle.");
                }

                // Otherwise, wait for it to be freed.
                try
                {
                    wait();
                }
                catch (InterruptedException ex)
                {
                }
            }

            // Get the usage count.
            usage = getUsageCount(bundle, ref, svcObj);
            // If there is no cached services, then just return immediately.
            if (usage == null)
            {
                return false;
            }
            // if this is a call from service objects and the service was not fetched from
            // there, return false
            if ( svcObj != null )
            {
            	if ( usage.m_serviceObjectsCount > 0 )
            	{
            		usage.m_serviceObjectsCount--;
            	}
            	else
            	{
            		return false;
            	}
            }
            // Lock the service registration.
            m_lockedRegsMap.put(reg, Thread.currentThread());
        }

        // If usage count will go to zero, then unget the service
        // from the registration; we do this outside the lock
        // since this might call out to the service factory.
        try
        {
            if (usage.m_count == 1)
            {
                // Remove reference from usages array.
                ((ServiceRegistrationImpl.ServiceReferenceImpl) ref)
                    .getRegistration().ungetService(bundle, usage.m_svcObj);
            }
        }
        finally
        {
            // Finally, decrement usage count and flush if it goes to zero or
            // the registration became invalid while we were not holding the
            // lock. Either way, unlock the service registration so that any
            // threads waiting for it can continue.
            synchronized (this)
            {
                // Decrement usage count, which spec says should happen after
                // ungetting the service object.
                usage.m_count--;

                // If the registration is invalid or the usage count has reached
                // zero, then flush it.
                if (!reg.isValid() || (usage.m_count <= 0))
                {
                    usage.m_svcObj = null;
                    flushUsageCount(bundle, ref, usage);
                }

                // Release the registration lock so any waiting threads can
                // continue.
                m_lockedRegsMap.remove(reg);
                notifyAll();
            }
        }

        return true;
    }


    /**
     * This is a utility method to release all services being
     * used by the specified bundle.
     * @param bundle the bundle whose services are to be released.
    **/
    public void ungetServices(Bundle bundle)
    {
        UsageCount[] usages;
        synchronized (this)
        {
            usages = m_inUseMap.get(bundle);
        }

        if (usages == null)
        {
            return;
        }

        // Note, there is no race condition here with respect to the
        // bundle using more services, because its bundle context
        // has already been invalidated by this point, so it would not
        // be able to look up more services.

        // Remove each service object from the
        // service cache.
        for (int i = 0; i < usages.length; i++)
        {
            // Keep ungetting until all usage count is zero.
            while (ungetService(bundle, usages[i].m_ref, usages[i].m_prototype ? usages[i].m_svcObj : null))
            {
                // Empty loop body.
            }
        }
    }

    public synchronized Bundle[] getUsingBundles(ServiceReference<?> ref)
    {
        Bundle[] bundles = null;
        for (Iterator<Map.Entry<Bundle, UsageCount[]>> iter = m_inUseMap.entrySet().iterator(); iter.hasNext(); )
        {
            Map.Entry<Bundle, UsageCount[]> entry = iter.next();
            Bundle bundle = entry.getKey();
            UsageCount[] usages = entry.getValue();
            for (int useIdx = 0; useIdx < usages.length; useIdx++)
            {
                if (usages[useIdx].m_ref.equals(ref))
                {
                    // Add the bundle to the array to be returned.
                    if (bundles == null)
                    {
                        bundles = new Bundle[] { bundle };
                    }
                    else
                    {
                        Bundle[] nbs = new Bundle[bundles.length + 1];
                        System.arraycopy(bundles, 0, nbs, 0, bundles.length);
                        nbs[bundles.length] = bundle;
                        bundles = nbs;
                    }
                }
            }
        }
        return bundles;
    }

    void servicePropertiesModified(ServiceRegistration<?> reg, Dictionary oldProps)
    {
        updateHook(reg.getReference());
        if (m_callbacks != null)
        {
            m_callbacks.serviceChanged(
                new ServiceEvent(ServiceEvent.MODIFIED, reg.getReference()), oldProps);
        }
    }

    public Logger getLogger()
    {
        return m_logger;
    }

    private static ServiceRegistration<?>[] addServiceRegistration(
        ServiceRegistration<?>[] regs, ServiceRegistration<?> reg)
    {
        if (regs == null)
        {
            regs = new ServiceRegistration[] { reg };
        }
        else
        {
            ServiceRegistration<?>[] newRegs = new ServiceRegistration[regs.length + 1];
            System.arraycopy(regs, 0, newRegs, 0, regs.length);
            newRegs[regs.length] = reg;
            regs = newRegs;
        }
        return regs;
    }

    private static ServiceRegistration<?>[] removeServiceRegistration(
        ServiceRegistration<?>[] regs, ServiceRegistration<?> reg)
    {
        for (int i = 0; (regs != null) && (i < regs.length); i++)
        {
            if (regs[i].equals(reg))
            {
                // If this is the only usage, then point to empty list.
                if ((regs.length - 1) == 0)
                {
                    regs = new ServiceRegistration[0];
                }
                // Otherwise, we need to do some array copying.
                else
                {
                    ServiceRegistration<?>[] newRegs = new ServiceRegistration[regs.length - 1];
                    System.arraycopy(regs, 0, newRegs, 0, i);
                    if (i < newRegs.length)
                    {
                        System.arraycopy(
                            regs, i + 1, newRegs, i, newRegs.length - i);
                    }
                    regs = newRegs;
                }
            }
        }
        return regs;
    }

    /**
     * Utility method to retrieve the specified bundle's usage count for the
     * specified service reference.
     * @param bundle The bundle whose usage counts are being searched.
     * @param ref The service reference to find in the bundle's usage counts.
     * @return The associated usage count or null if not found.
    **/
    private UsageCount getUsageCount(Bundle bundle, ServiceReference<?> ref, final Object svcObj)
    {
        UsageCount[] usages = m_inUseMap.get(bundle);
        for (int i = 0; (usages != null) && (i < usages.length); i++)
        {
            if (usages[i].m_ref.equals(ref) 
               && ((svcObj == null && !usages[i].m_prototype) || usages[i].m_svcObj == svcObj))
            {
                return usages[i];
            }
        }
        return null;
    }

    /**
     * Utility method to update the specified bundle's usage count array to
     * include the specified service. This method should only be called
     * to add a usage count for a previously unreferenced service. If the
     * service already has a usage count, then the existing usage count
     * counter simply needs to be incremented.
     * @param bundle The bundle acquiring the service.
     * @param ref The service reference of the acquired service.
     * @param svcObj The service object of the acquired service.
    **/
    private UsageCount addUsageCount(Bundle bundle, ServiceReference<?> ref, boolean isPrototype)
    {
        UsageCount[] usages = m_inUseMap.get(bundle);

        UsageCount usage = new UsageCount();
        usage.m_ref = ref;
        usage.m_prototype = isPrototype;

        if (usages == null)
        {
            usages = new UsageCount[] { usage };
        }
        else
        {
            UsageCount[] newUsages = new UsageCount[usages.length + 1];
            System.arraycopy(usages, 0, newUsages, 0, usages.length);
            newUsages[usages.length] = usage;
            usages = newUsages;
        }

        m_inUseMap.put(bundle, usages);

        return usage;
    }

    /**
     * Utility method to flush the specified bundle's usage count for the
     * specified service reference. This should be called to completely
     * remove the associated usage count object for the specified service
     * reference. If the goal is to simply decrement the usage, then get
     * the usage count and decrement its counter. This method will also
     * remove the specified bundle from the "in use" map if it has no more
     * usage counts after removing the usage count for the specified service
     * reference.
     * @param bundle The bundle whose usage count should be removed.
     * @param ref The service reference whose usage count should be removed.
    **/
    private void flushUsageCount(Bundle bundle, ServiceReference<?> ref, UsageCount uc)
    {
        UsageCount[] usages = m_inUseMap.get(bundle);
        for (int i = 0; (usages != null) && (i < usages.length); i++)
        {
            if ((uc == null && usages[i].m_ref.equals(ref)) || (uc == usages[i]))
            {
                // If this is the only usage, then point to empty list.
                if ((usages.length - 1) == 0)
                {
                    usages = null;
                }
                // Otherwise, we need to do some array copying.
                else
                {
                    UsageCount[] newUsages = new UsageCount[usages.length - 1];
                    System.arraycopy(usages, 0, newUsages, 0, i);
                    if (i < newUsages.length)
                    {
                        System.arraycopy(
                            usages, i + 1, newUsages, i, newUsages.length - i);
                    }
                    usages = newUsages;
                    i--;
                }
            }
        }

        if (usages != null)
        {
            m_inUseMap.put(bundle, usages);
        }
        else
        {
            m_inUseMap.remove(bundle);
        }
    }

    //
    // Hook-related methods.
    //

    boolean isHookBlackListed(ServiceReference<?> sr)
    {
        return m_blackList.containsKey(sr);
    }

    void blackListHook(ServiceReference<?> sr)
    {
        m_blackList.put(sr, sr);
    }

    static boolean isHook(String[] classNames, Class<?> hookClass, Object svcObj)
    {
        // For a service factory, we can only match names.
        if (svcObj instanceof ServiceFactory)
        {
            for (String className : classNames)
            {
                if (className.equals(hookClass.getName()))
                {
                    return true;
                }
            }
        }

        // For a service object, check if its class matches.
        if (hookClass.isAssignableFrom(svcObj.getClass()))
        {
            // But still only if it is registered under that interface.
            String hookName = hookClass.getName();
            for (String className : classNames)
            {
                if (className.equals(hookName))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private void addHooks(String[] classNames, Object svcObj, ServiceReference<?> ref)
    {
        for (Class<?> hookClass : m_hookClasses)
        {
            if (isHook(classNames, hookClass, svcObj))
            {
                synchronized (m_allHooks)
                {
                    Set<ServiceReference<?>> hooks = m_allHooks.get(hookClass);
                    if (hooks == null)
                    {
                        hooks = new TreeSet<ServiceReference<?>>(Collections.reverseOrder());
                        m_allHooks.put(hookClass, hooks);
                    }
                    hooks.add(ref);
                }
            }
        }
    }

    private void updateHook(ServiceReference<?> ref)
    {
        // We maintain the hooks sorted, so if ranking has changed for example,
        // we need to ensure the order remains correct by resorting the hooks.
        Object svcObj = ((ServiceRegistrationImpl.ServiceReferenceImpl) ref)
                .getRegistration().getService();
        String [] classNames = (String[]) ref.getProperty(Constants.OBJECTCLASS);

        for (Class<?> hookClass : m_hookClasses)
        {
            if (isHook(classNames, hookClass, svcObj))
            {
                synchronized (m_allHooks)
                {
                    Set<ServiceReference<?>> hooks = m_allHooks.get(hookClass);
                    if (hooks != null)
                    {
                        List<ServiceReference<?>> refs = new ArrayList<ServiceReference<?>>(hooks);
                        hooks.clear();
                        hooks.addAll(refs);
                    }
                }
            }
        }
    }

    private void removeHook(ServiceReference<?> ref)
    {
        Object svcObj = ((ServiceRegistrationImpl.ServiceReferenceImpl) ref)
            .getRegistration().getService();
        String [] classNames = (String[]) ref.getProperty(Constants.OBJECTCLASS);

        for (Class<?> hookClass : m_hookClasses)
        {
            if (isHook(classNames, hookClass, svcObj))
            {
                synchronized (m_allHooks)
                {
                    Set<ServiceReference<?>> hooks = m_allHooks.get(hookClass);
                    if (hooks != null)
                    {
                        hooks.remove(ref);
                        if (hooks.isEmpty())
                        {
                            m_allHooks.remove(hookClass);
                        }
                    }
                }
            }
        }
    }

    public <S> Set<ServiceReference<S>> getHooks(Class<S> hookClass)
    {
        synchronized (m_allHooks)
        {
            Set<ServiceReference<?>> hooks = m_allHooks.get(hookClass);
            if (hooks != null)
            {
                SortedSet<ServiceReference<?>> sorted = new TreeSet<ServiceReference<?>>(Collections.reverseOrder());
                sorted.addAll(hooks);
                return asTypedSortedSet(sorted);
            }
            return Collections.emptySet();
        }
    }

    private static <S> SortedSet<ServiceReference<S>> asTypedSortedSet(
        SortedSet<ServiceReference<?>> ss)
    {
        return (SortedSet) ss;
    }

    private static class UsageCount
    {
        public int m_count;
        public ServiceReference<?> m_ref;
        public Object m_svcObj;
        public boolean m_prototype;
        public int m_serviceObjectsCount;
    }

    public interface ServiceRegistryCallbacks
    {
        void serviceChanged(ServiceEvent event, Dictionary oldProps);
    }
}
