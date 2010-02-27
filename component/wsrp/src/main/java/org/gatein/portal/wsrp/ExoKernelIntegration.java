/*
 * JBoss, a division of Red Hat
 * Copyright 2010, Red Hat Middleware, LLC, and individual
 * contributors as indicated by the @authors tag. See the
 * copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.gatein.portal.wsrp;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.jcr.ext.hierarchy.NodeHierarchyCreator;
import org.gatein.common.logging.Logger;
import org.gatein.common.logging.LoggerFactory;
import org.gatein.pc.api.PortletInvoker;
import org.gatein.pc.federation.FederatingPortletInvoker;
import org.gatein.pc.portlet.container.ContainerPortletInvoker;
import org.gatein.pc.portlet.impl.state.StateConverterV0;
import org.gatein.pc.portlet.impl.state.StateManagementPolicyService;
import org.gatein.pc.portlet.impl.state.producer.PortletStatePersistenceManagerService;
import org.gatein.pc.portlet.state.StateConverter;
import org.gatein.pc.portlet.state.producer.PortletStatePersistenceManager;
import org.gatein.pc.portlet.state.producer.ProducerPortletInvoker;
import org.gatein.portal.wsrp.state.consumer.JCRConsumerRegistry;
import org.gatein.portal.wsrp.state.producer.configuration.JCRProducerConfigurationService;
import org.gatein.portal.wsrp.state.producer.registrations.JCRRegistrationPersistenceManager;
import org.gatein.portal.wsrp.state.producer.state.JCRPortletStatePersistenceManager;
import org.gatein.registration.RegistrationManager;
import org.gatein.registration.RegistrationPersistenceManager;
import org.gatein.registration.impl.RegistrationManagerImpl;
import org.gatein.wci.ServletContainer;
import org.gatein.wci.ServletContainerFactory;
import org.gatein.wci.WebApp;
import org.gatein.wci.WebAppEvent;
import org.gatein.wci.WebAppLifeCycleEvent;
import org.gatein.wci.WebAppListener;
import org.gatein.wci.impl.DefaultServletContainerFactory;
import org.gatein.wsrp.api.SessionEvent;
import org.gatein.wsrp.api.SessionEventBroadcaster;
import org.gatein.wsrp.api.SessionEventListener;
import org.gatein.wsrp.consumer.registry.ActivatingNullInvokerHandler;
import org.gatein.wsrp.consumer.registry.ConsumerRegistry;
import org.gatein.wsrp.producer.ProducerHolder;
import org.gatein.wsrp.producer.WSRPProducer;
import org.gatein.wsrp.producer.config.ProducerConfigurationService;
import org.picocontainer.Startable;

import javax.servlet.ServletContext;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author <a href="mailto:chris.laprun@jboss.com">Chris Laprun</a>
 * @version $Revision$
 */
public class ExoKernelIntegration implements Startable, WebAppListener
{
   private static final Logger log = LoggerFactory.getLogger(ExoKernelIntegration.class);

   private static final String CLASSPATH = "classpath:/";
   private static final String PRODUCER_CONFIG_LOCATION = "producerConfigLocation";
   private static final String CONSUMERS_CONFIG_LOCATION = "consumersConfigLocation";

   private final InputStream configurationIS;
   private final String producerConfigLocation;
   private WSRPProducer producer;

   private ConsumerRegistry consumerRegistry;
   private ExoContainer container;
   private final boolean bypass;
   private static final String WSRP_ADMIN_GUI_CONTEXT_PATH = "/wsrp-admin-gui";

   public ExoKernelIntegration(ExoContainerContext context, InitParams params, ConfigurationManager configurationManager,
                               org.exoplatform.portal.pc.ExoKernelIntegration pc, NodeHierarchyCreator nhc) throws Exception
   {
      // IMPORTANT: even though PC ExoKernelIntegration and NodeHierarchyCreator is not used anywhere in the code, it's still needed for pico
      // to properly make sure that this service is started after the PC one. Yes, Pico is crap. :/

      // todo: we currently only allow the service to go through initialization if we are running in the default portal
      // as this service is not meant to work with extensions yet...
      String consumersConfigLocation;
      if ("portal".equals(context.getName()))
      {
         if (params != null)
         {
            producerConfigLocation = params.getValueParam(PRODUCER_CONFIG_LOCATION).getValue();
            consumersConfigLocation = params.getValueParam(CONSUMERS_CONFIG_LOCATION).getValue();
         }
         else
         {
            throw new IllegalArgumentException("Improperly configured service: missing values for "
               + PRODUCER_CONFIG_LOCATION + "and " + CONSUMERS_CONFIG_LOCATION);
         }

         configurationIS = configurationManager.getInputStream(CLASSPATH + producerConfigLocation);

         container = context.getContainer();

         bypass = false;
      }
      else
      {
         log.info("The WSRP service can only be started in the default portal context. WSRP was not started for '"
            + context.getName() + "'");

         producerConfigLocation = null;
         consumersConfigLocation = null;
         configurationIS = null;
         bypass = true;
      }
   }

   public void start()
   {
      if (!bypass)
      {
         startProducer();
         startConsumers();

         // listen for web app events so that we can inject services into WSRP admin UI "cleanly"
         // todo: this service injection should really be done using CDI... :/
         ServletContainerFactory factory = DefaultServletContainerFactory.getInstance();
         ServletContainer servletContainer = factory.getServletContainer();
         servletContainer.addWebAppListener(this);
      }
   }

   private void startProducer()
   {

      JCRProducerConfigurationService producerConfigurationService;
      try
      {
         producerConfigurationService = new JCRProducerConfigurationService(container);
         producerConfigurationService.setDefaultConfigurationIS(configurationIS);
         producerConfigurationService.reloadConfiguration();
      }
      catch (Exception e)
      {
         throw new RuntimeException("Couldn't load WSRP producer configuration from " + producerConfigLocation, e);
      }
      container.registerComponentInstance(ProducerConfigurationService.class, producerConfigurationService);

      RegistrationPersistenceManager registrationPersistenceManager;
      try
      {
         registrationPersistenceManager = new JCRRegistrationPersistenceManager(container);
      }
      catch (Exception e)
      {
         throw new RuntimeException("Couln't instantiate RegistrationPersistenceManager", e);
      }
      RegistrationManager registrationManager = new RegistrationManagerImpl();
      registrationManager.setPersistenceManager(registrationPersistenceManager);

      // retrieve container portlet invoker from eXo kernel
      ContainerPortletInvoker containerPortletInvoker =
         (ContainerPortletInvoker)container.getComponentInstanceOfType(ContainerPortletInvoker.class);

      // The producer persistence manager
      PortletStatePersistenceManager producerPersistenceManager;
      try
      {
         producerPersistenceManager = new JCRPortletStatePersistenceManager(container);
      }
      catch (Exception e)
      {
         throw new RuntimeException("Couldn't instantiate PortletStatePersistenceManager", e);
      }

      // The producer state management policy
      StateManagementPolicyService producerStateManagementPolicy = new StateManagementPolicyService();
      producerStateManagementPolicy.setPersistLocally(true);

      // The producer state converter
      StateConverter producerStateConverter = new StateConverterV0();

      // The producer portlet invoker
      ProducerPortletInvoker producerPortletInvoker = new ProducerPortletInvoker();
      producerPortletInvoker.setNext(containerPortletInvoker);
      producerPortletInvoker.setPersistenceManager(producerPersistenceManager);
      producerPortletInvoker.setStateManagementPolicy(producerStateManagementPolicy);
      producerPortletInvoker.setStateConverter(producerStateConverter);

      // create and wire WSRP producer
      producer = ProducerHolder.getProducer(true);
      producer.setPortletInvoker(producerPortletInvoker);
      producer.setRegistrationManager(registrationManager);
      producer.setConfigurationService(producerConfigurationService);

      producer.start();
   }

   private void startConsumers()
   {
      // retrieve federating portlet invoker from container
      FederatingPortletInvoker federatingPortletInvoker =
         (FederatingPortletInvoker)container.getComponentInstanceOfType(PortletInvoker.class);

      try
      {
         consumerRegistry = new JCRConsumerRegistry(container);
         consumerRegistry.setFederatingPortletInvoker(federatingPortletInvoker);
         consumerRegistry.setSessionEventBroadcaster(new SimpleSessionEventBroadcaster());
         consumerRegistry.start();

         // set up a NullInvokerHandler so that when a remote producer is queried, we can start it if needed
         ActivatingNullInvokerHandler handler = new ActivatingNullInvokerHandler();
         handler.setConsumerRegistry(consumerRegistry);
         federatingPortletInvoker.setNullInvokerHandler(handler);
      }
      catch (Exception e)
      {
         throw new RuntimeException("Couldn't start WSRP consumers registry.", e);
      }
      container.registerComponentInstance(ConsumerRegistry.class, consumerRegistry);
   }

   public void stop()
   {
      if (!bypass)
      {
         // stop listening to web app events
         ServletContainerFactory factory = DefaultServletContainerFactory.getInstance();
         ServletContainer servletContainer = factory.getServletContainer();
         servletContainer.removeWebAppListener(this);

         stopProducer();
         stopConsumers();
      }
   }

   private void stopProducer()
   {
      producer.stop();

      producer = null;
   }

   private void stopConsumers()
   {
      try
      {
         consumerRegistry.stop();
      }
      catch (Exception e)
      {
         throw new RuntimeException("Couldn't stop WSRP consumers registry.");
      }

      consumerRegistry = null;
   }

   public void onEvent(WebAppEvent event)
   {
      if (event instanceof WebAppLifeCycleEvent)
      {
         WebAppLifeCycleEvent lifeCycleEvent = (WebAppLifeCycleEvent)event;
         WebApp webApp = event.getWebApp();
         ServletContext context = webApp.getServletContext();

         // if we see the WSRP admin GUI being deployed or undeployed, inject or remove services 
         if (WSRP_ADMIN_GUI_CONTEXT_PATH.equals(webApp.getContextPath()))
         {
            switch (lifeCycleEvent.getType())
            {
               case WebAppLifeCycleEvent.ADDED:
                  context.setAttribute("ConsumerRegistry", consumerRegistry);
                  context.setAttribute("ProducerConfigurationService", producer.getConfigurationService());
                  break;
               case WebAppLifeCycleEvent.REMOVED:
                  context.removeAttribute("ConsumerRegistry");
                  context.removeAttribute("ProducerConfigurationService");
                  break;
            }
         }
      }
   }

   private static class SimpleSessionEventBroadcaster implements SessionEventBroadcaster
   {
      private Map<String, SessionEventListener> listeners = new ConcurrentHashMap<String, SessionEventListener>();

      public void registerListener(String listenerId, SessionEventListener listener)
      {
         listeners.put(listenerId, listener);
      }

      public void unregisterListener(String listenerId)
      {
         listeners.remove(listenerId);
      }

      public void notifyListenersOf(SessionEvent event)
      {
         for (SessionEventListener listener : listeners.values())
         {
            listener.onSessionEvent(event);
         }
      }

   }
}
