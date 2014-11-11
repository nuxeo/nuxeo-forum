/*
 * (C) Copyright 2006-2007 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     ${user}
 *
 * $Id
 */

package org.nuxeo.ecm.platform.forum.web;

import java.io.Serializable;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.faces.application.FacesMessage;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.web.RequestParameter;
import org.jboss.seam.core.Events;
import org.jboss.seam.faces.FacesMessages;
import org.jbpm.graph.exe.ProcessInstance;
import org.jbpm.taskmgmt.exe.TaskInstance;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.repository.Repository;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.platform.comment.web.CommentManagerActions;
import org.nuxeo.ecm.platform.forum.web.api.PostAction;
import org.nuxeo.ecm.platform.forum.web.api.ThreadAction;
import org.nuxeo.ecm.platform.forum.workflow.ForumConstants;
import org.nuxeo.ecm.platform.forum.workflow.GetModerationTaskOperation;
import org.nuxeo.ecm.platform.jbpm.JbpmEventNames;
import org.nuxeo.ecm.platform.jbpm.JbpmService;
import org.nuxeo.ecm.platform.jbpm.JbpmService.VariableName;
import org.nuxeo.ecm.platform.ui.web.api.NavigationContext;
import org.nuxeo.ecm.platform.util.RepositoryLocation;
import org.nuxeo.ecm.webapp.helpers.ResourcesAccessor;
import org.nuxeo.runtime.api.Framework;

/**
 * This action listener is used to create a Post inside a Thread and also to
 * handle the moderation cycle on Post.
 *
 * @author <a href="bchaffangeon@nuxeo.com">Brice Chaffangeon</a>
 */
@Name("postAction")
@Scope(ScopeType.CONVERSATION)
public class PostActionBean implements PostAction {

    private static final long serialVersionUID = 1L;

    private static final Log log = LogFactory.getLog(PostActionBean.class);

    @In(create = true)
    protected ThreadAction threadAction;

    @In(create = true)
    protected transient CommentManagerActions commentManagerActions;

    @In(create = true, required = false)
    protected transient CoreSession documentManager;

    @In(create = true)
    protected transient NavigationContext navigationContext;

    @In(create = true)
    protected transient JbpmService jbpmService;

    @In(required = false)
    protected RepositoryLocation currentServerLocation;

    @In(create = true)
    protected transient Principal currentUser;

    @In(create = true, required = false)
    protected FacesMessages facesMessages;

    @In(create = true)
    protected ResourcesAccessor resourcesAccessor;

    // the id of the comment to delete
    @RequestParameter
    protected String deletePostId;

    protected String title;

    protected String text;

    protected String filename;

    protected Blob fileContent;

    public boolean checkWritePermissionOnThread() {
        try {
            DocumentModel currentDocument = navigationContext.getCurrentDocument();
            if (currentDocument != null) {
                return documentManager.hasPermission(currentDocument.getRef(),
                        SecurityConstants.READ_WRITE);
            } else {
                log.error("Cannot check write permission on thread: "
                        + "no current document found");
            }
        } catch (ClientException e) {
            log.error(e);
        }
        return false;
    }

    protected void fetchInvalidationsIfNeeded() throws ClientException {
        // fetch invalidations from unrestricted session if needed
        if (!documentManager.isStateSharedByAllThreadSessions()) {
            documentManager.save();
        }
    }

    /**
     * Adds the post to the thread and starts the moderation WF on the post
     * created.
     */
    public String addPost() throws ClientException {
        DocumentModel dm = documentManager.createDocumentModel("Post");

        dm.setProperty("post", "author",
                commentManagerActions.getPrincipalName());

        dm.setProperty("post", "title", title);
        dm.setProperty("post", "text", text);
        dm.setProperty("post", "creationDate", new Date());
        dm.setProperty("post", "filename", filename);
        dm.setProperty("post", "fileContent", fileContent);

        // save it to the repository
        dm = commentManagerActions.addComment(dm);

        if (threadAction.isCurrentThreadModerated()
                && !threadAction.isPrincipalModerator()) {
            // start moderation workflow + warn user that post
            // won't be displayed until moderation kicks in
            startModeration(dm);
            facesMessages.add(FacesMessage.SEVERITY_INFO,
                    resourcesAccessor.getMessages().get(
                            "label.comment.waiting_approval"));
        } else {
            // publish post
            DocumentRef postRef = dm.getRef();
            if (documentManager.hasPermission(postRef,
                    SecurityConstants.WRITE_LIFE_CYCLE)) {
                documentManager.followTransition(postRef,
                        ForumConstants.TRANSITION_TO_PUBLISHED_STATE);
                documentManager.save();
            } else {
                // Here user only granted with read rights should be able to
                // create a post => open a system session to put it in published
                // state
                LoginContext loginContext = null;
                CoreSession systemSession = null;
                try {
                    loginContext = Framework.login();
                    RepositoryManager mgr = Framework.getService(RepositoryManager.class);
                    Repository repo = mgr.getRepository(currentServerLocation.getName());
                    systemSession = repo.open();

                    // follow transition
                    systemSession.followTransition(dm.getRef(),
                            ForumConstants.TRANSITION_TO_PUBLISHED_STATE);

                    systemSession.save();
                } catch (Exception e) {
                    throw new ClientException(e);
                } finally {
                    if (loginContext != null) {
                        try {
                            loginContext.logout();
                        } catch (LoginException e) {
                        }
                    }
                    if (systemSession != null) {
                        CoreInstance.getInstance().close(systemSession);
                    }
                }
            }
            fetchInvalidationsIfNeeded();
            // NXP-1262 display the message only when about to publish
            facesMessages.add(FacesMessage.SEVERITY_INFO,
                    resourcesAccessor.getMessages().get(
                            "label.comment.added.sucess"));
        }

        // force comment manager to reload posts
        commentManagerActions.documentChanged();
        cleanContextVariables();

        return navigationContext.navigateToDocument(getParentThread());
    }

    public String cancelPost() throws ClientException {
        cleanContextVariables();
        commentManagerActions.cancelComment();
        fetchInvalidationsIfNeeded();
        return navigationContext.navigateToDocument(getParentThread());
    }

    public String deletePost() throws ClientException {
        if (deletePostId == null) {
            throw new ClientException("No id for post to delete");
        }

        DocumentModel thread = getParentThread();
        DocumentModel post = documentManager.getDocument(new IdRef(deletePostId));

        if (threadAction.isThreadModerated(thread)
                && ForumConstants.PENDING_STATE.equals(post.getCurrentLifeCycleState())) {
            ProcessInstance process = getModerationProcess(thread, deletePostId);
            if (process != null) {
                jbpmService.endProcessInstance(process.getId());
            }
        }
        commentManagerActions.deleteComment(deletePostId);

        fetchInvalidationsIfNeeded();
        Events.instance().raiseEvent(JbpmEventNames.WORKFLOW_ENDED);

        return navigationContext.navigateToDocument(getParentThread());
    }

    public String rejectPost(DocumentModel post) throws ClientException {
        DocumentModel thread = getParentThread();

        TaskInstance moderationTask = getModerationTask(thread, post.getId());
        if (moderationTask == null) {
            throw new ClientException("No moderation task found");
        }

        jbpmService.endTask(moderationTask.getId(),
                ForumConstants.PROCESS_TRANSITION_TO_REJECTED, null, null, null, (NuxeoPrincipal) currentUser);

        Events.instance().raiseEvent(JbpmEventNames.WORKFLOW_TASK_COMPLETED);

        // force comment manager to reload posts
        commentManagerActions.documentChanged();

        fetchInvalidationsIfNeeded();

        return navigationContext.navigateToDocument(getParentThread());
    }

    /**
     * Ends the task on a post.
     */
    public String approvePost(DocumentModel post) throws ClientException {
        DocumentModel thread = getParentThread();

        TaskInstance moderationTask = getModerationTask(thread, post.getId());
        if (moderationTask == null) {
            throw new ClientException("No moderation task found");
        }

        jbpmService.endTask(moderationTask.getId(),
                ForumConstants.PROCESS_TRANSITION_TO_PUBLISH, null, null, null, (NuxeoPrincipal)currentUser);

        Events.instance().raiseEvent(JbpmEventNames.WORKFLOW_TASK_COMPLETED);

        // force comment manager to reload posts
        commentManagerActions.documentChanged();

        fetchInvalidationsIfNeeded();

        return navigationContext.navigateToDocument(getParentThread());
    }

    public DocumentModel getParentThread() {
        return navigationContext.getCurrentDocument();
    }

    public boolean isPostPublished(DocumentModel post) throws ClientException {
        boolean published = false;
        if (post != null
                && ForumConstants.PUBLISHED_STATE.equals(post.getCurrentLifeCycleState())) {
            published = true;
        }
        return published;
    }

    /**
     * Starts the moderation on given Post.
     */
    @SuppressWarnings("unchecked")
    protected void startModeration(DocumentModel post) throws ClientException {

        DocumentModel thread = getParentThread();
        ArrayList<String> moderators = (ArrayList<String>) thread.getProperty(
                "thread", "moderators");

        if (moderators == null || moderators.isEmpty()) {
            throw new ClientException("No moderators defined");
        }

        Map<String, Serializable> vars = new HashMap<String, Serializable>();
        vars.put(VariableName.participants.name(), moderators);
        vars.put(ForumConstants.COMMENT_ID, post.getId());
        jbpmService.createProcessInstance((NuxeoPrincipal) currentUser,
                ForumConstants.PROCESS_INSTANCE_NAME, thread, vars, null);
        Events.instance().raiseEvent(JbpmEventNames.WORKFLOW_NEW_STARTED);

    }

    protected ProcessInstance getModerationProcess(DocumentModel thread,
            String postId) throws ClientException {
        List<ProcessInstance> processes = jbpmService.getProcessInstances(
                thread, (NuxeoPrincipal) currentUser, new ForumWorkflowFilter(
                        postId));
        if (processes != null && !processes.isEmpty()) {
            if (processes.size() > 1) {
                log.error("There are several moderation workflows running, "
                        + "taking only first found");
            }
            return processes.get(0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    protected TaskInstance getModerationTask(DocumentModel thread, String postId)
            throws ClientException {
        ProcessInstance process = getModerationProcess(thread, postId);
        GetModerationTaskOperation operation = new GetModerationTaskOperation(process.getId());
        return (TaskInstance) jbpmService.executeJbpmOperation(operation);
    }

    protected void cleanContextVariables() {
        fileContent = null;
        filename = null;
        text = null;
        title = null;
    }

    // getters/setters

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Blob getFileContent() {
        return fileContent;
    }

    public void setFileContent(Blob fileContent) {
        this.fileContent = fileContent;
    }

    /**
     * Gets the title of the post for creation purpose. If the post to be
     * created reply to a previous post, the title of the new post comes with
     * the previous title, and a prefix (i.e : Re : Previous Title).
     */
    public String getTitle() throws ClientException {

        String previousId = commentManagerActions.getSavedReplyCommentId();
        if (previousId != null && !"".equals(previousId)) {
            DocumentModel previousPost = documentManager.getDocument(new IdRef(
                    previousId));

            // Test to ensure that previous comment got the "post" schema
            if (previousPost.getDataModel("post") != null) {
                String previousTitle = (String) previousPost.getProperty(
                        "post", "title");
                String prefix = resourcesAccessor.getMessages().get(
                        "label.forum.post.title.prefix");
                title = prefix + previousTitle;
            }
        }
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

}
