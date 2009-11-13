/**
 * Copyright (C) 2009 eXo Platform SAS.
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

package org.exoplatform.portal.config;

import org.exoplatform.commons.utils.PageList;
import org.exoplatform.container.component.ComponentPlugin;
import org.exoplatform.portal.config.model.Application;
import org.exoplatform.portal.config.model.Container;
import org.exoplatform.portal.pom.data.ModelChange;
import org.exoplatform.portal.config.model.ModelObject;
import org.exoplatform.portal.config.model.Page;
import org.exoplatform.portal.config.model.PageNavigation;
import org.exoplatform.portal.config.model.PageNode;
import org.exoplatform.portal.config.model.PortalConfig;
import org.exoplatform.portal.config.model.TransientApplicationState;
import org.exoplatform.portal.pom.config.ModelDemarcation;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.Group;
import org.exoplatform.services.organization.OrganizationService;
import org.picocontainer.Startable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by The eXo Platform SAS Apr 19, 2007 This service is used to load the
 * PortalConfig, Page config and Navigation config for a given user.
 */
public class UserPortalConfigService implements Startable
{

   public final static String CREATE_PAGE_EVENT = "UserPortalConfigService.page.onCreate".intern();

   public final static String REMOVE_PAGE_EVENT = "UserPortalConfigService.page.onRemove".intern();

   public final static String UPDATE_PAGE_EVENT = "UserPortalConfigService.page.onUpdate".intern();

   public final static String CREATE_NAVIGATION_EVENT = "UserPortalConfigService.navigation.onCreate".intern();

   public final static String REMOVE_NAVIGATION_EVENT = "UserPortalConfigService.navigation.onRemove".intern();

   public final static String UPDATE_NAVIGATION_EVENT = "UserPortalConfigService.navigation.onUpdate".intern();

   private DataStorage storage_;

   private UserACL userACL_;

   private OrganizationService orgService_;

   private ListenerService listenerService;

   private NewPortalConfigListener newPortalConfigListener_;

   private Log log = ExoLogger.getLogger("Portal:UserPortalConfigService");

   public UserPortalConfigService(UserACL userACL, DataStorage storage,
      OrganizationService orgService, ListenerService listenerService) throws Exception
   {
      this.storage_ = storage;
      this.orgService_ = orgService;
      this.listenerService = listenerService;
      this.userACL_ = userACL;
   }

   /**
    * <p>
    * Build and returns an instance of <tt>UserPortalConfig</tt>.
    * </p>
    * 
    * <p>
    * To return a valid config, the current thread must be associated with an
    * identity that will grant him access to the portal as returned by the
    * {@link UserACL#hasPermission(org.exoplatform.portal.config.model.PortalConfig)}
    * method.
    * </p>
    * 
    * <p>
    * The navigation loaded on the
    * <tt>UserPortalConfig<tt> object are obtained according to the specified user
    * argument. The portal navigation is always loaded. If the specified user is null then the navigation of the guest
    * group as configured by {@link org.exoplatform.portal.config.UserACL#getGuestsGroup()} is also loaded, otherwise
    * the navigations are loaded according to the following rules:
    *
    * <ul>
    * <li>The navigation corresponding to the user is loaded.</li>
    * <li>When the user is root according to the value returned by {@link org.exoplatform.portal.config.UserACL#getSuperUser()}
    * then the navigation of all groups are loaded.</li>
    * <li>When the user is not root, then all its groups are added except the guest group as configued per
    * {@link org.exoplatform.portal.config.UserACL#getGuestsGroup()}.</li>
    * </ul>
    *
    * All the navigations are sorted using the value returned by {@link org.exoplatform.portal.config.model.PageNavigation#getPriority()}.
    * </p>
    *
    * @param portalName the portal name
    * @param accessUser the user name
    * @return the config
    * @throws Exception any exception
    */
   public UserPortalConfig getUserPortalConfig(String portalName, String accessUser) throws Exception
   {
      PortalConfig portal = storage_.getPortalConfig(portalName);
      if (portal == null || !userACL_.hasPermission(portal))
         return null;

      List<PageNavigation> navigations = new ArrayList<PageNavigation>();
      PageNavigation navigation = getPageNavigation(PortalConfig.PORTAL_TYPE, portalName);
      if (navigation != null)
      {
         navigation.setModifiable(userACL_.hasPermission(portal.getEditPermission()));
         navigations.add(navigation);
      }

      if (accessUser == null)
      {
         // navigation = getPageNavigation(PortalConfig.GROUP_TYPE,
         // userACL_.getGuestsGroup());
         // if (navigation != null)
         // navigations.add(navigation);
      }
      else
      {
         navigation = getPageNavigation(PortalConfig.USER_TYPE, accessUser);
         if (navigation != null)
         {
            navigation.setModifiable(true);
            navigations.add(navigation);
         }

         Collection<?> groups = null;
         if (userACL_.getSuperUser().equals(accessUser))
            groups = orgService_.getGroupHandler().getAllGroups();
         else
            groups = orgService_.getGroupHandler().findGroupsOfUser(accessUser);
         for (Object group : groups)
         {
            Group m = (Group)group;
            String groupId = m.getId().trim();
            if (groupId.equals(userACL_.getGuestsGroup()))
               continue;
            navigation = getPageNavigation(PortalConfig.GROUP_TYPE, groupId);
            if (navigation == null)
               continue;
            navigation.setModifiable(userACL_.hasEditPermission(navigation));
            navigations.add(navigation);
         }
      }
      Collections.sort(navigations, new Comparator<PageNavigation>()
      {
         public int compare(PageNavigation nav1, PageNavigation nav2)
         {
            return nav1.getPriority() - nav2.getPriority();
         }
      });

      return new UserPortalConfig(portal, navigations);
   }

   /**
    * Compute and returns the list that the specified user can manage. If the
    * user is root then all existing groups are returned otherwise the list is
    * computed from the groups in which the user has a configured membership.
    * The membership is configured from the value returned by
    * {@link org.exoplatform.portal.config.UserACL#getMakableMT()}
    * 
    * @param remoteUser
    *           the user to get the makable navigations
    * @return the list of groups
    * @throws Exception
    *            any exception
    */
   public List<String> getMakableNavigations(String remoteUser) throws Exception
   {
      List<String> list = new ArrayList<String>();
      Collection<?> groups = null;
      if (remoteUser.equals(userACL_.getSuperUser()))
         groups = orgService_.getGroupHandler().getAllGroups();
      else
         groups = orgService_.getGroupHandler().findGroupByMembership(remoteUser, userACL_.getMakableMT());
      if (groups != null)
      {
         for (Object group : groups)
         {
            Group m = (Group)group;
            String groupId = m.getId().trim();
            list.add(groupId);
         }
      }
      return list;
   }

   /**
    * This method should create a the portal config, pages and navigation
    * according to the template name.
    * 
    * @param portalName
    *           the portal name
    * @param template
    *           the template to use
    * @throws Exception
    *            any exception
    */
   public void createUserPortalConfig(String ownerType, String portalName, String template) throws Exception
   {
      NewPortalConfig portalConfig = newPortalConfigListener_.getPortalConfig(ownerType);

      //
      portalConfig.setTemplateOwner(template);
      portalConfig.getPredefinedOwner().clear();
      portalConfig.getPredefinedOwner().add(portalName);

      //
      newPortalConfigListener_.initPortletPreferencesDB(portalConfig);
      newPortalConfigListener_.initPortalConfigDB(portalConfig);
      newPortalConfigListener_.initPageDB(portalConfig);
      newPortalConfigListener_.initPageNavigationDB(portalConfig);
   }

   /**
    * This method removes the PortalConfig, Page and PageNavigation that belong
    * to the portal in the database.
    * 
    * @param portalName
    *           the portal name
    * @throws Exception
    *            any exception
    */
   public void removeUserPortalConfig(String portalName) throws Exception
   {
      removeUserPortalConfig("portal", portalName);
   }

   /**
    * This method removes the PortalConfig, Page and PageNavigation that belong
    * to the portal in the database.
    * 
    * @param ownerType
    *           the owner type
    * @param ownerId
    *           the portal name
    * @throws Exception
    *            any exception
    */
   public void removeUserPortalConfig(String ownerType, String ownerId) throws Exception
   {
      PortalConfig config = storage_.getPortalConfig(ownerType, ownerId);
      if (config != null)
      {
         storage_.remove(config);
      }
   }

   /**
    * This method should update the PortalConfig object
    * 
    * @param portal
    * @throws Exception
    */
   public void update(PortalConfig portal) throws Exception
   {
      storage_.save(portal);
   }

   /**
    * This method load the page according to the pageId and returns.
    * 
    * @param pageId
    *           the page id
    * @return the page
    * @throws Exception
    *            any exception
    */
   public Page getPage(String pageId) throws Exception
   {
      if (pageId == null)
         return null;
      return storage_.getPage(pageId); // TODO: pageConfigCache_ needs to be
   }

   /**
    * This method load the page according to the pageId and returns it if the
    * current thread is associated with an identity that allows to view the page
    * according to the
    * {@link UserACL#hasPermission(org.exoplatform.portal.config.model.Page)}
    * method.
    * 
    * @param pageId
    *           the page id
    * @param accessUser
    *           never used
    * @return the page
    * @throws Exception
    *            any exception
    */
   public Page getPage(String pageId, String accessUser) throws Exception
   {
      Page page = getPage(pageId);
      if (page == null || !userACL_.hasPermission(page))
      {
         return null;
      }
      return page;
   }

   /**
    * Removes a page and broadcast an event labelled as
    * {@link org.exoplatform.portal.config.UserPortalConfigService#REMOVE_PAGE_EVENT}
    * when the removal is successful.
    * 
    * @param page
    *           the page to remove
    * @throws Exception
    *            any exception
    */
   public void remove(Page page) throws Exception
   {
      storage_.remove(page);
      listenerService.broadcast(REMOVE_PAGE_EVENT, this, page);
   }

   /**
    * Creates a page and broadcast an event labelled as
    * {@link org.exoplatform.portal.config.UserPortalConfigService#CREATE_PAGE_EVENT}
    * when the creation is successful.
    * 
    * @param page
    *           the page to create
    * @throws Exception
    *            any exception
    */
   public void create(Page page) throws Exception
   {
      storage_.create(page);

      //
      listenerService.broadcast(CREATE_PAGE_EVENT, this, page);
   }

   /**
    * Updates a page and broadcast an event labelled as
    * {@link org.exoplatform.portal.config.UserPortalConfigService#UPDATE_PAGE_EVENT}
    * when the creation is successful.
    * 
    * @param page
    *           the page to update
    * @return the list of model changes that occured
    * @throws Exception
    *            any exception
    */
   public List<ModelChange> update(Page page) throws Exception
   {
      List<ModelChange> changes = storage_.save(page);

      //
      listenerService.broadcast(UPDATE_PAGE_EVENT, this, page);
      return changes;
   }

   /**
    * Creates a navigation and broadcast an event labelled as
    * {@link org.exoplatform.portal.config.UserPortalConfigService#CREATE_NAVIGATION_EVENT}
    * when the creation is successful.
    * 
    * @param navigation
    *           the navigation to create
    * @throws Exception
    *            any exception
    */
   public void create(PageNavigation navigation) throws Exception
   {
      storage_.create(navigation);
      listenerService.broadcast(CREATE_NAVIGATION_EVENT, this, navigation);
   }

   /**
    * Updates a page navigation broadcast an event labelled as
    * {@link org.exoplatform.portal.config.UserPortalConfigService#UPDATE_NAVIGATION_EVENT}
    * when the creation is successful.
    * 
    * @param navigation
    *           the navigation to update
    * @throws Exception
    *            any exception
    */
   public void update(PageNavigation navigation) throws Exception
   {
      storage_.save(navigation);
      listenerService.broadcast(UPDATE_NAVIGATION_EVENT, this, navigation);
   }

   /**
    * Removes a navigation and broadcast an event labelled as
    * {@link org.exoplatform.portal.config.UserPortalConfigService#REMOVE_NAVIGATION_EVENT}
    * when the removal is successful.
    * 
    * @param navigation
    *           the navigation to remove
    * @throws Exception
    *            any exception
    */
   public void remove(PageNavigation navigation) throws Exception
   {
      storage_.remove(navigation);
      listenerService.broadcast(REMOVE_NAVIGATION_EVENT, this, navigation);
   }

   public PageNavigation getPageNavigation(String ownerType, String id) throws Exception
   {
      PageNavigation navigation = storage_.getPageNavigation(ownerType, id);
      return navigation;
   }

   /**
    * This method creates new page from an existing page and links new page to a
    * PageNode.
    * 
    * @param nodeName
    * @param nodeLabel
    * @param pageId
    * @param ownerType
    * @param ownerId
    * @return
    * @throws Exception
    */
   public PageNode createNodeFromPageTemplate(String nodeName, String nodeLabel, String pageId, String ownerType,
      String ownerId) throws Exception
   {
      Page page = renewPage(pageId, nodeName, ownerType, ownerId);
      PageNode pageNode = new PageNode();
      if (nodeLabel == null || nodeLabel.trim().length() < 1)
         nodeLabel = nodeName;
      pageNode.setName(nodeName);
      pageNode.setLabel(nodeLabel);
      pageNode.setPageReference(page.getPageId());
      return pageNode;
   }

   /**
    * Clones a page.
    * 
    * @param pageId
    *           the id of the page to clone
    * @param pageName
    *           the new page name
    * @param ownerType
    *           the new page owner type
    * @param ownerId
    *           the new page owner id
    * @return the newly created page
    * @throws Exception
    *            any exception
    */
   public Page renewPage(String pageId, String pageName, String ownerType, String ownerId) throws Exception
   {
      return storage_.clonePage(pageId, ownerType, ownerId, pageName);
   }

   /**
    * Creates a page from an existing template.
    * 
    * @param temp
    *           the template name
    * @param ownerType
    *           the new owner type
    * @param ownerId
    *           the new owner id
    * @return the page
    * @throws Exception
    *            any exception
    */
   public Page createPageTemplate(String temp, String ownerType, String ownerId) throws Exception
   {
      Page page = newPortalConfigListener_.createPageFromTemplate(ownerType, ownerId, temp);
      updateOwnership(page, ownerType, ownerId);
      return page;
   }

   /**
    * Load all navigation that user has edit permission.
    * 
    * @return the navigation the user can edit
    * @throws Exception
    *            any exception
    */
   public List<PageNavigation> loadEditableNavigations() throws Exception
   {
      Query<PageNavigation> query = new Query<PageNavigation>(PortalConfig.GROUP_TYPE, null, PageNavigation.class);
      List<PageNavigation> navis = storage_.find(query, new Comparator<PageNavigation>()
      {
         public int compare(PageNavigation pconfig1, PageNavigation pconfig2)
         {
            return pconfig1.getOwnerId().compareTo(pconfig2.getOwnerId());
         }
      }).getAll();

      //
      List<PageNavigation> navigations = new ArrayList<PageNavigation>();
      for (PageNavigation ele : navis)
      {
         if (userACL_.hasEditPermission(ele))
         {
            navigations.add(ele);
         }
      }
      return navigations;
   }

   /**
    * Returns the list of group ids having navigation.
    * 
    * @return the group id having navigation
    * @throws Exception
    *            any exception
    */
   public Set<String> findGroupHavingNavigation() throws Exception
   {
      Query<PageNavigation> query = new Query<PageNavigation>(PortalConfig.GROUP_TYPE, null, PageNavigation.class);
      Set<String> groupIds = new HashSet<String>();
      List<PageNavigation> navis = storage_.find(query).getAll();
      for (PageNavigation ele : navis)
      {
         groupIds.add(ele.getOwnerId());
      }
      return groupIds;
   }

   /**
    * Returns the list of all portal names.
    * 
    * @return the list of all portal names
    * @throws Exception
    *            any exception
    */
   public List<String> getAllPortalNames() throws Exception
   {
      List<String> list = new ArrayList<String>();
      Query<PortalConfig> query = new Query<PortalConfig>("portal", null, null, null, PortalConfig.class);
      PageList<PortalConfig> pageList = storage_.find(query);
      List<PortalConfig> configs = pageList.getAll();
      for (PortalConfig ele : configs)
      {
         if (userACL_.hasPermission(ele))
         {
            list.add(ele.getName());
         }
      }
      return list;
   }

   /**
    * Update the ownership recursively on the model graph.
    * 
    * @param object
    *           the model object graph root
    * @param ownerType
    *           the new owner type
    * @param ownerId
    *           the new owner id
    */
   private void updateOwnership(ModelObject object, String ownerType, String ownerId)
   {
      if (object instanceof Container)
      {
         Container container = (Container)object;
         if (container instanceof Page)
         {
            Page page = (Page)container;
            page.setOwnerType(ownerType);
            page.setOwnerId(ownerId);
         }
         for (ModelObject child : container.getChildren())
         {
            updateOwnership(child, ownerType, ownerId);
         }
      }
      else if (object instanceof Application)
      {
         Application application = (Application)object;
         TransientApplicationState applicationState = (TransientApplicationState)application.getState();
         if (applicationState != null
            && (applicationState.getOwnerType() == null || applicationState.getOwnerId() == null))
         {
            applicationState.setOwnerType(ownerType);
            applicationState.setOwnerId(ownerId);
         }
      }
   }

   public void initListener(ComponentPlugin listener)
   {
      if (listener instanceof NewPortalConfigListener)
      {
         synchronized (this)
         {
            if (newPortalConfigListener_ == null)
            {
               this.newPortalConfigListener_ = (NewPortalConfigListener)listener;
            }
            else
            {
               newPortalConfigListener_.addPortalConfigs((NewPortalConfigListener)listener);
            }
         }
      }
   }

   public void start()
   {
      try
      {
         if (newPortalConfigListener_ == null)
            return;

         //
         if (storage_ instanceof ModelDemarcation)
         {
            ((ModelDemarcation)storage_).begin();
         }

         newPortalConfigListener_.run();
      }
      catch (Exception e)
      {
         log.error("Could not import initial data", e);

         //
         if (storage_ instanceof ModelDemarcation)
         {
            ((ModelDemarcation)storage_).end(false);
         }
      }
      finally
      {
         if (storage_ instanceof ModelDemarcation)
         {
            ((ModelDemarcation)storage_).end(true);
         }
      }
   }

   public void stop()
   {
   }

   public String getDefaultPortal()
   {
      return newPortalConfigListener_.getDefaultPortal();
   }
}
