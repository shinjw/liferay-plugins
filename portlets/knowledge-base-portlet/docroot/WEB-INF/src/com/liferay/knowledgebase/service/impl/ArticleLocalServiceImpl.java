/**
 * Copyright (c) 2000-2010 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.knowledgebase.service.impl;

import com.liferay.documentlibrary.DuplicateDirectoryException;
import com.liferay.documentlibrary.DuplicateFileException;
import com.liferay.documentlibrary.NoSuchDirectoryException;
import com.liferay.knowledgebase.ArticleContentException;
import com.liferay.knowledgebase.ArticleTitleException;
import com.liferay.knowledgebase.admin.social.AdminActivityKeys;
import com.liferay.knowledgebase.model.Article;
import com.liferay.knowledgebase.model.ArticleConstants;
import com.liferay.knowledgebase.service.base.ArticleLocalServiceBaseImpl;
import com.liferay.knowledgebase.util.comparator.ArticlePriorityComparator;
import com.liferay.knowledgebase.util.comparator.ArticleVersionComparator;
import com.liferay.portal.kernel.dao.orm.DynamicQuery;
import com.liferay.portal.kernel.dao.orm.DynamicQueryFactoryUtil;
import com.liferay.portal.kernel.dao.orm.Property;
import com.liferay.portal.kernel.dao.orm.PropertyFactoryUtil;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.messaging.Message;
import com.liferay.portal.kernel.messaging.MessageBusUtil;
import com.liferay.portal.kernel.portlet.PortletClassLoaderUtil;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.HttpUtil;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.OrderByComparator;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.model.Company;
import com.liferay.portal.model.CompanyConstants;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.GroupConstants;
import com.liferay.portal.model.Layout;
import com.liferay.portal.model.LayoutTypePortlet;
import com.liferay.portal.model.ResourceConstants;
import com.liferay.portal.model.User;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.ServiceContextUtil;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.PortletKeys;
import com.liferay.util.portlet.PortletProps;

import java.io.IOException;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.portlet.PortletPreferences;

/**
 * <a href="ArticleLocalServiceImpl.java.html"><b><i>View Source</i></b></a>
 *
 * @author Peter Shin
 * @author Brian Wing Shun Chan
 */
public class ArticleLocalServiceImpl extends ArticleLocalServiceBaseImpl {

	public Article addArticle(
			String uuid, long userId, long parentResourcePrimKey, String title,
			String content, String description, int priority, String dirName,
			ServiceContext serviceContext)
		throws PortalException, SystemException {

		// See transaction.manager.impl in portal.properties.

		// Article

		User user = userPersistence.findByPrimaryKey(userId);
		Date now = new Date();

		validate(title, content);

		long articleId = counterLocalService.increment();

		long resourcePrimKey = counterLocalService.increment();

		Article article = articlePersistence.create(articleId);

		article.setUuid(uuid);
		article.setResourcePrimKey(resourcePrimKey);
		article.setGroupId(serviceContext.getScopeGroupId());
		article.setCompanyId(user.getCompanyId());
		article.setUserId(user.getUserId());
		article.setUserName(user.getFullName());
		article.setCreateDate(serviceContext.getCreateDate(now));
		article.setModifiedDate(serviceContext.getModifiedDate(now));
		article.setParentResourcePrimKey(parentResourcePrimKey);
		article.setVersion(ArticleConstants.DEFAULT_VERSION);
		article.setTitle(title);
		article.setContent(content);
		article.setDescription(description);
		article.setPriority(priority);
		article.setExpandoBridgeAttributes(serviceContext);

		articlePersistence.update(article, false);

		// Resources

		if (serviceContext.getAddCommunityPermissions() ||
			serviceContext.getAddGuestPermissions()) {

			addArticleResources(
				article, serviceContext.getAddCommunityPermissions(),
				serviceContext.getAddGuestPermissions());
		}
		else {
			addArticleResources(
				article, serviceContext.getCommunityPermissions(),
				serviceContext.getGuestPermissions());
		}

		// Articles

		updateDisplayOrder(article, parentResourcePrimKey, priority);

		// Message Boards

		mbMessageLocalService.addDiscussionMessage(
			userId, article.getUserName(), Article.class.getName(),
			resourcePrimKey, WorkflowConstants.ACTION_PUBLISH);

		// Social

		socialActivityLocalService.addActivity(
			userId, article.getGroupId(), Article.class.getName(),
			resourcePrimKey, AdminActivityKeys.ADD_ARTICLE, StringPool.BLANK,
			0);

		// Indexer

		Indexer indexer = IndexerRegistryUtil.getIndexer(Article.class);

		indexer.reindex(article);

		// Attachments

		addArticleAttachments(article, dirName);

		// Subscriptions

		notifySubscribers(article, false, serviceContext);

		return article;
	}

	public void addArticleAttachments(Article article, String dirName)
		throws PortalException, SystemException {

		// Database and file system operations are processed independently.

		String articleDirName = article.getAttachmentsDirName();

		try {
			dlService.addDirectory(
				article.getCompanyId(), CompanyConstants.SYSTEM,
				articleDirName);
		}
		catch (DuplicateDirectoryException dde) {
			_log.error("Directory already exists for " + dde.getMessage());
		}

		if (Validator.isNull(dirName)) {
			return;
		}

		String[] fileNames = dlService.getFileNames(
			article.getCompanyId(), CompanyConstants.SYSTEM, dirName);

		for (String fileName : fileNames) {
			byte[] bytes = dlService.getFile(
				article.getCompanyId(), CompanyConstants.SYSTEM, fileName);

			try {
				addAttachment(
					article.getCompanyId(), articleDirName,
					FileUtil.getShortFileName(fileName), bytes);
			}
			catch (DuplicateFileException dfe) {
				_log.error("File already exists for " + dfe.getMessage());
			}
		}
	}

	public void addArticleResources(
			Article article, boolean addCommunityPermissions,
			boolean addGuestPermissions)
		throws PortalException, SystemException {

		resourceLocalService.addResources(
			article.getCompanyId(), article.getGroupId(), article.getUserId(),
			Article.class.getName(), article.getResourcePrimKey(), false,
			addCommunityPermissions, addGuestPermissions);
	}

	public void addArticleResources(
			Article article, String[] communityPermissions,
			String[] guestPermissions)
		throws PortalException, SystemException {

		resourceLocalService.addModelResources(
			article.getCompanyId(), article.getGroupId(), article.getUserId(),
			Article.class.getName(), article.getResourcePrimKey(),
			communityPermissions, guestPermissions);
	}

	public void addAttachment(
			long companyId, String dirName, String shortFileName, byte[] bytes)
		throws PortalException, SystemException {

		ServiceContext serviceContext = new ServiceContext();

		dlService.addFile(
			companyId, CompanyConstants.SYSTEM_STRING,
			GroupConstants.DEFAULT_PARENT_GROUP_ID, CompanyConstants.SYSTEM,
			dirName + StringPool.SLASH + shortFileName, 0, StringPool.BLANK,
			serviceContext.getCreateDate(null), serviceContext, bytes);
	}

	public void checkAttachments() throws PortalException, SystemException {
		for (long companyId : PortalUtil.getCompanyIds()) {
			checkAttachments(companyId);
		}
	}

	public void deleteArticle(long resourcePrimKey)
		throws PortalException, SystemException {

		// Article

		Article article = articlePersistence.findByResourcePrimKey_First(
			resourcePrimKey, new ArticleVersionComparator());

		// Child Articles

		List<Article> articles = getGroupArticles(
			article.getGroupId(), article.getResourcePrimKey(),
			QueryUtil.ALL_POS, QueryUtil.ALL_POS,
			new ArticlePriorityComparator());

		for (Article curArticle : articles) {
			deleteArticle(curArticle.getResourcePrimKey());
		}

		deleteArticle(article);
	}

	public void deleteArticle(Article article)
		throws PortalException, SystemException {

		// See transaction.manager.impl in portal.properties.

		// Resources

		resourceLocalService.deleteResource(
			article.getCompanyId(), Article.class.getName(),
			ResourceConstants.SCOPE_INDIVIDUAL, article.getResourcePrimKey());

		// Articles

		articlePersistence.removeByResourcePrimKey(
			article.getResourcePrimKey());

		// Expando

		expandoValueLocalService.deleteValues(
			Article.class.getName(), article.getResourcePrimKey());

		// Message boards

		mbMessageLocalService.deleteDiscussionMessages(
			Article.class.getName(), article.getResourcePrimKey());

		// Social

		socialActivityLocalService.deleteActivities(
			Article.class.getName(), article.getResourcePrimKey());

		// Indexer

		Indexer indexer = IndexerRegistryUtil.getIndexer(Article.class);

		indexer.delete(article);

		// Attachments

		// Database and file system operations are processed independently.

		try {
			dlService.deleteDirectory(
				article.getCompanyId(), CompanyConstants.SYSTEM_STRING,
				CompanyConstants.SYSTEM, article.getAttachmentsDirName());
		}
		catch (NoSuchDirectoryException nsde) {
			_log.error("No directory found for " + nsde.getMessage());
		}

		// Subscriptions

		subscriptionLocalService.deleteSubscriptions(
			article.getCompanyId(), Article.class.getName(),
			article.getResourcePrimKey());
	}

	public void deleteAttachment(long companyId, String fileName)
		throws PortalException, SystemException {

		dlService.deleteFile(
			companyId, CompanyConstants.SYSTEM_STRING, CompanyConstants.SYSTEM,
			fileName);
	}

	public void deleteGroupArticles(long groupId)
		throws PortalException, SystemException {

		List<Article> articles = getGroupArticles(
			groupId, 0, QueryUtil.ALL_POS, QueryUtil.ALL_POS,
			new ArticlePriorityComparator());

		for (Article article : articles) {
			deleteArticle(article.getResourcePrimKey());
		}
	}

	public Article getArticle(long resourcePrimKey, int version)
		throws PortalException, SystemException {

		return articlePersistence.findByR_V(resourcePrimKey, version);
	}

	public List<Article> getArticles(
			long resourcePrimKey, int start, int end,
			OrderByComparator orderByComparator)
		throws SystemException {

		return articlePersistence.findByResourcePrimKey(
			resourcePrimKey, start, end, orderByComparator);
	}

	public int getArticlesCount(long resourcePrimKey) throws SystemException {
		return articlePersistence.countByResourcePrimKey(resourcePrimKey);
	}

	public List<Article> getCompanyArticles(
			long companyId, int start, int end,
			OrderByComparator orderByComparator)
		throws SystemException {

		Map<String, Long> params = new HashMap<String, Long>();

		params.put("companyId", new Long(companyId));

		return dynamicQuery(
			getDynamicQuery(params), start, end, orderByComparator);
	}

	public int getCompanyArticlesCount(long companyId) throws SystemException {
		Map<String, Long> params = new HashMap<String, Long>();

		params.put("companyId", new Long(companyId));

		return (int)dynamicQueryCount(getDynamicQuery(params));
	}

	public List<Article> getGroupArticles(
			long groupId, int start, int end,
			OrderByComparator orderByComparator)
		throws SystemException {

		Map<String, Long> params = new HashMap<String, Long>();

		params.put("groupId", new Long(groupId));

		return dynamicQuery(
			getDynamicQuery(params), start, end, orderByComparator);
	}

	public List<Article> getGroupArticles(
			long groupId, long parentResourcePrimKey, int start, int end,
			OrderByComparator orderByComparator)
		throws SystemException {

		Map<String, Long> params = new HashMap<String, Long>();

		params.put("groupId", new Long(groupId));
		params.put("parentResourcePrimKey", new Long(parentResourcePrimKey));

		return dynamicQuery(
			getDynamicQuery(params), start, end, orderByComparator);
	}

	public int getGroupArticlesCount(long groupId) throws SystemException {
		Map<String, Long> params = new HashMap<String, Long>();

		params.put("groupId", new Long(groupId));

		return (int)dynamicQueryCount(getDynamicQuery(params));
	}

	public int getGroupArticlesCount(long groupId, long parentResourcePrimKey)
		throws SystemException {

		Map<String, Long> params = new HashMap<String, Long>();

		params.put("groupId", new Long(groupId));
		params.put("parentResourcePrimKey", new Long(parentResourcePrimKey));

		return (int)dynamicQueryCount(getDynamicQuery(params));
	}

	public Article getLatestArticle(long resourcePrimKey)
		throws PortalException, SystemException {

		return articlePersistence.findByResourcePrimKey_First(
			resourcePrimKey, new ArticleVersionComparator());
	}

	public void subscribe(long groupId, long userId, long resourcePrimKey)
		throws PortalException, SystemException {

		if (resourcePrimKey <= 0) {
			subscriptionLocalService.addSubscription(
				userId, Article.class.getName(), groupId);
		}
		else {
			subscriptionLocalService.addSubscription(
				userId, Article.class.getName(), resourcePrimKey);
		}
	}

	public void unsubscribe(long groupId, long userId, long resourcePrimKey)
		throws PortalException, SystemException {

		if (resourcePrimKey <= 0) {
			subscriptionLocalService.deleteSubscription(
				userId, Article.class.getName(), groupId);
		}
		else {
			subscriptionLocalService.deleteSubscription(
				userId, Article.class.getName(), resourcePrimKey);
		}
	}

	public Article updateArticle(
			long userId, long resourcePrimKey, long parentResourcePrimKey,
			String title, String content, String description, int priority,
			String dirName, ServiceContext serviceContext)
		throws PortalException, SystemException {

		// See transaction.manager.impl in portal.properties.

		// Article

		User user = userPersistence.findByPrimaryKey(userId);
		Article oldArticle = articlePersistence.findByResourcePrimKey_First(
			resourcePrimKey, new ArticleVersionComparator());

		validate(title, content);

		long articleId = counterLocalService.increment();

		Article article = articlePersistence.create(articleId);

		article.setResourcePrimKey(oldArticle.getResourcePrimKey());
		article.setGroupId(oldArticle.getGroupId());
		article.setCompanyId(oldArticle.getCompanyId());
		article.setUserId(user.getUserId());
		article.setUserName(user.getFullName());
		article.setCreateDate(oldArticle.getCreateDate());
		article.setModifiedDate(serviceContext.getModifiedDate(null));
		article.setParentResourcePrimKey(parentResourcePrimKey);
		article.setVersion(oldArticle.getVersion() + 1);
		article.setTitle(title);
		article.setContent(content);
		article.setDescription(description);
		article.setPriority(priority);
		article.setExpandoBridgeAttributes(serviceContext);

		articlePersistence.update(article, false);

		// Resources

		if ((serviceContext.getCommunityPermissions() != null) ||
			(serviceContext.getGuestPermissions() != null)) {

			updateArticleResources(
				article, serviceContext.getCommunityPermissions(),
				serviceContext.getGuestPermissions());
		}

		// Articles

		updateDisplayOrder(article, parentResourcePrimKey, priority);

		// Social

		socialActivityLocalService.addActivity(
			userId, article.getGroupId(), Article.class.getName(),
			resourcePrimKey, AdminActivityKeys.UPDATE_ARTICLE, StringPool.BLANK,
			0);

		// Indexer

		Indexer indexer = IndexerRegistryUtil.getIndexer(Article.class);

		indexer.reindex(article);

		// Attachments

		updateArticleAttachments(article, dirName);

		// Subscriptions

		notifySubscribers(article, true, serviceContext);

		return article;
	}

	public void updateArticleAttachments(Article article, String dirName)
		throws PortalException, SystemException {

		// Database and file system operations are processed independently.

		if (Validator.isNull(dirName)) {
			return;
		}

		try {
			dlService.deleteDirectory(
				article.getCompanyId(), CompanyConstants.SYSTEM_STRING,
				CompanyConstants.SYSTEM, article.getAttachmentsDirName());
		}
		catch (NoSuchDirectoryException nsde) {
			_log.error("No directory found for " + nsde.getMessage());
		}

		addArticleAttachments(article, dirName);
	}

	public void updateArticleResources(
			Article article, String[] communityPermissions,
			String[] guestPermissions)
		throws PortalException, SystemException {

		resourceLocalService.updateResources(
			article.getCompanyId(), article.getGroupId(),
			Article.class.getName(), article.getResourcePrimKey(),
			communityPermissions, guestPermissions);
	}

	public String updateAttachments(long companyId, long resourcePrimKey)
		throws PortalException, SystemException {

		String dirName =
			"knowledgebase/temp/attachments/" + counterLocalService.increment();

		dlService.addDirectory(companyId, CompanyConstants.SYSTEM, dirName);

		if (resourcePrimKey <= 0) {
			return dirName;
		}

		Article article = getLatestArticle(resourcePrimKey);

		for (String fileName : article.getAttachmentsFileNames()) {
			String shortFileName = FileUtil.getShortFileName(fileName);

			byte[] bytes = dlService.getFile(
				article.getCompanyId(), CompanyConstants.SYSTEM, fileName);

			addAttachment(companyId, dirName, shortFileName, bytes);
		}

		return dirName;
	}

	public Article updateDisplayOrder(
			Article article, long parentResourcePrimKey, int priority)
		throws SystemException {

		List<Article> articles = ListUtil.copy(
			getGroupArticles(
				article.getGroupId(), article.getParentResourcePrimKey(),
				QueryUtil.ALL_POS, QueryUtil.ALL_POS,
				new ArticlePriorityComparator(true)));

		articles.remove(article);
		articles.add(priority, article);

		for (int i = 0; i < articles.size(); i++) {
			Article curArticle = articles.get(i);

			long curParentResourcePrimKey =
				curArticle.getParentResourcePrimKey();

			if (priority == i) {
				curParentResourcePrimKey = parentResourcePrimKey;
			}

			List<Article> childrenArticles =
				articlePersistence.findByResourcePrimKey(
					curArticle.getResourcePrimKey());

			for (Article childrenArticle : childrenArticles) {
				childrenArticle.setParentResourcePrimKey(
					curParentResourcePrimKey);
				childrenArticle.setPriority(i);

				articlePersistence.update(childrenArticle, false);

				if (article.getArticleId() == childrenArticle.getArticleId()) {
					article = childrenArticle;
				}
			}
		}

		return article;
	}

	protected void checkAttachments(long companyId)
		throws PortalException, SystemException {

		String dirName =
			"knowledgebase/temp/attachments/" + counterLocalService.increment();

		dlService.addDirectory(companyId, CompanyConstants.SYSTEM, dirName);

		String[] fileNames = dlService.getFileNames(
			companyId, CompanyConstants.SYSTEM,
			"knowledgebase/temp/attachments");

		Arrays.sort(fileNames);

		for (int i = 0; i < fileNames.length - 50; i++) {
			dlService.deleteDirectory(
				companyId, CompanyConstants.SYSTEM_STRING,
				CompanyConstants.SYSTEM, fileNames[i]);
		}

		dlService.deleteDirectory(
			companyId, CompanyConstants.SYSTEM_STRING, CompanyConstants.SYSTEM,
			dirName);
	}

	protected DynamicQuery getDynamicQuery(Map<String, Long> params) {
		DynamicQuery subselectDynamicQuery = DynamicQueryFactoryUtil.forClass(
			Article.class, "article2", PortletClassLoaderUtil.getClassLoader());

		subselectDynamicQuery.setProjection(
			PropertyFactoryUtil.forName("version").max());

		Property resourcePrimKeyProperty1 = PropertyFactoryUtil.forName(
			"article1.resourcePrimKey");
		Property resourcePrimKeyProperty2 = PropertyFactoryUtil.forName(
			"article2.resourcePrimKey");

		subselectDynamicQuery.add(
			resourcePrimKeyProperty1.eqProperty(resourcePrimKeyProperty2));

		DynamicQuery dynamicQuery = DynamicQueryFactoryUtil.forClass(
			Article.class, "article1", PortletClassLoaderUtil.getClassLoader());

		for (Map.Entry<String, Long> entry : params.entrySet()) {
			String name = entry.getKey();

			Property property = PropertyFactoryUtil.forName(name);

			dynamicQuery.add(property.eq(entry.getValue()));
		}

		dynamicQuery.add(
			PropertyFactoryUtil.forName("version").in(subselectDynamicQuery));

		return dynamicQuery;
	}

	protected void notifySubscribers(
			Article article, boolean update, ServiceContext serviceContext)
		throws PortalException, SystemException {

		if (Validator.isNull(serviceContext.getLayoutFullURL())) {
			return;
		}

		PortletPreferences preferences =
			ServiceContextUtil.getPortletPreferences(serviceContext);

		if (preferences == null) {
			long ownerId = article.getGroupId();
			int ownerType = PortletKeys.PREFS_OWNER_TYPE_GROUP;
			long plid = PortletKeys.PREFS_PLID_SHARED;
			String portletId = PortletKeys.KNOWLEDGE_BASE_ADMIN;
			String defaultPreferences = null;

			preferences = portletPreferencesLocalService.getPreferences(
				article.getCompanyId(), ownerId, ownerType, plid, portletId,
				defaultPreferences);
		}

		String emailArticleAddedEnabled = preferences.getValue(
			"email-article-added-enabled",
			PortletProps.get("admin.email.article.added.enabled"));

		if (!update && !GetterUtil.getBoolean(emailArticleAddedEnabled)) {
			return;
		}

		String emailArticleUpdatedEnabled = preferences.getValue(
			"email-article-updated-enabled",
			PortletProps.get("admin.email.article.updated.enabled"));

		if (update && !GetterUtil.getBoolean(emailArticleUpdatedEnabled)) {
			return;
		}

		Company company = companyPersistence.findByPrimaryKey(
			article.getCompanyId());

		Group group = groupPersistence.findByPrimaryKey(
			serviceContext.getScopeGroupId());

		User user = userPersistence.fetchByPrimaryKey(article.getUserId());

		String fullName = article.getUserName();
		String emailAddress = StringPool.BLANK;

		if (user != null) {
			fullName = user.getFullName();
			emailAddress = user.getEmailAddress();
		}

		String portletName = PortalUtil.getPortletTitle(
			PortletKeys.KNOWLEDGE_BASE_ADMIN, LocaleUtil.getDefault());

		String fromName = preferences.getValue(
			"email-from-name", PortletProps.get("admin.email.from.name"));
		String fromAddress = preferences.getValue(
			"email-from-address", PortletProps.get("admin.email.from.address"));

		fromName = StringUtil.replace(
			fromName,
			new String[] {
				"[$ARTICLE_USER_ADDRESS$]",
				"[$ARTICLE_USER_NAME$]",
				"[$COMPANY_ID$]",
				"[$COMPANY_MX$]",
				"[$COMPANY_NAME$]",
				"[$COMMUNITY_NAME$]",
				"[$PORTLET_NAME$]"
			},
			new String[] {
				emailAddress,
				fullName,
				String.valueOf(company.getCompanyId()),
				company.getMx(),
				company.getName(),
				group.getName(),
				portletName
			});

		fromAddress = StringUtil.replace(
			fromAddress,
			new String[] {
				"[$ARTICLE_USER_ADDRESS$]",
				"[$ARTICLE_USER_NAME$]",
				"[$COMPANY_ID$]",
				"[$COMPANY_MX$]",
				"[$COMPANY_NAME$]",
				"[$COMMUNITY_NAME$]",
				"[$PORTLET_NAME$]"
			},
			new String[] {
				emailAddress,
				fullName,
				String.valueOf(company.getCompanyId()),
				company.getMx(),
				company.getName(),
				group.getName(),
				portletName
			});

		String articleURL = null;

		Layout layout = layoutLocalService.getLayout(serviceContext.getPlid());
		LayoutTypePortlet layoutTypePortlet =
			(LayoutTypePortlet)layout.getLayoutType();

		if (layoutTypePortlet.hasDefaultScopePortletId(
				article.getGroupId(), PortletKeys.KNOWLEDGE_BASE_ADMIN)) {

			String namespace = PortalUtil.getPortletNamespace(
				PortletKeys.KNOWLEDGE_BASE_ADMIN);

			articleURL = HttpUtil.setParameter(
				serviceContext.getLayoutFullURL(), "p_p_id",
				PortletKeys.KNOWLEDGE_BASE_ADMIN);
			articleURL = HttpUtil.setParameter(
				articleURL, namespace + "jspPage", "/admin/view_article.jsp");
			articleURL = HttpUtil.setParameter(
				articleURL, namespace + "resourcePrimKey",
				article.getResourcePrimKey());
		}

		if (articleURL == null) {
			articleURL = serviceContext.getLayoutFullURL();
		}

		String subject = null;
		String body = null;

		if (!update) {
			subject = preferences.getValue("email-article-added-subject", null);

			if (subject == null) {
				String name = PortletProps.get(
					"admin.email.article.added.subject");

				try {
					subject = StringUtil.read(
						getClass().getClassLoader(), name);
				}
				catch (IOException ioe) {
					_log.error(ioe.getMessage());
				}
			}

			body = preferences.getValue("email-article-added-body", null);

			if (body == null) {
				String name = PortletProps.get(
					"admin.email.article.added.body");

				try {
					body = StringUtil.read(getClass().getClassLoader(), name);
				}
				catch (IOException ioe) {
					_log.error(ioe.getMessage());
				}
			}
		}
		else {
			subject = preferences.getValue(
				"email-article-updated-subject", null);

			if (subject == null) {
				String name = PortletProps.get(
					"admin.email.article.updated.subject");

				try {
					subject = StringUtil.read(
						getClass().getClassLoader(), name);
				}
				catch (IOException ioe) {
					_log.error(ioe.getMessage());
				}
			}

			body = preferences.getValue("email-article-updated-body", null);

			if (body == null) {
				String name = PortletProps.get(
					"admin.email.article.updated.body");

				try {
					body = StringUtil.read(getClass().getClassLoader(), name);
				}
				catch (IOException ioe) {
					_log.error(ioe.getMessage());
				}
			}
		}

		subject = StringUtil.replace(
			subject,
			new String[] {
				"[$ARTICLE_TITLE$]",
				"[$ARTICLE_URL$]",
				"[$ARTICLE_USER_ADDRESS$]",
				"[$ARTICLE_USER_NAME$]",
				"[$COMPANY_ID$]",
				"[$COMPANY_MX$]",
				"[$COMPANY_NAME$]",
				"[$COMMUNITY_NAME$]",
				"[$FROM_ADDRESS$]",
				"[$FROM_NAME$]",
				"[$PORTAL_URL$]",
				"[$PORTLET_NAME$]"
			},
			new String[] {
				article.getTitle(),
				articleURL,
				emailAddress,
				fullName,
				String.valueOf(company.getCompanyId()),
				company.getMx(),
				company.getName(),
				group.getName(),
				fromAddress,
				fromName,
				company.getVirtualHost(),
				portletName
			});

		body = StringUtil.replace(
			body,
			new String[] {
				"[$ARTICLE_TITLE$]",
				"[$ARTICLE_URL$]",
				"[$ARTICLE_USER_ADDRESS$]",
				"[$ARTICLE_USER_NAME$]",
				"[$COMPANY_ID$]",
				"[$COMPANY_MX$]",
				"[$COMPANY_NAME$]",
				"[$COMMUNITY_NAME$]",
				"[$FROM_ADDRESS$]",
				"[$FROM_NAME$]",
				"[$PORTAL_URL$]",
				"[$PORTLET_NAME$]"
			},
			new String[] {
				article.getTitle(),
				articleURL,
				emailAddress,
				fullName,
				String.valueOf(company.getCompanyId()),
				company.getMx(),
				company.getName(),
				group.getName(),
				fromAddress,
				fromName,
				company.getVirtualHost(),
				portletName
			});

		String mailId =
			StringPool.LESS_THAN + "knowledge_base.article." +
				article.getResourcePrimKey() + StringPool.AT +
					company.getMx() + StringPool.GREATER_THAN;

		Message message = new Message();

		message.put("companyId", article.getCompanyId());
		message.put("groupId", article.getGroupId());
		message.put("userId", article.getUserId());
		message.put("resourcePrimKey", article.getResourcePrimKey());
		message.put("fromName", fromName);
		message.put("fromAddress", fromAddress);
		message.put("subject", subject);
		message.put("body", body);
		message.put("replyToAddress", fromAddress);
		message.put("mailId", mailId);
		message.put("htmlFormat", Boolean.TRUE);

		MessageBusUtil.sendMessage("liferay/knowledge_base_admin", message);
	}

	protected void validate(String title, String content)
		throws PortalException {

		if (Validator.isNull(title)) {
			throw new ArticleTitleException();
		}

		if (Validator.isNull(content)) {
			throw new ArticleContentException();
		}
	}

	private static Log _log = LogFactoryUtil.getLog(
		ArticleLocalServiceImpl.class);

}