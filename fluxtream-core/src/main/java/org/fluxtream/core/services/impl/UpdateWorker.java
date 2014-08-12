package org.fluxtream.core.services.impl;

import java.util.Date;
import java.util.List;
import org.fluxtream.core.Configuration;
import org.fluxtream.core.aspects.FlxLogger;
import org.fluxtream.core.connectors.Connector;
import org.fluxtream.core.connectors.updaters.AbstractUpdater;
import org.fluxtream.core.connectors.updaters.SettingsAwareUpdater;
import org.fluxtream.core.connectors.updaters.SharedConnectorSettingsAwareUpdater;
import org.fluxtream.core.connectors.updaters.UpdateInfo;
import org.fluxtream.core.connectors.updaters.UpdateResult;
import org.fluxtream.core.domain.ApiKey;
import org.fluxtream.core.domain.ConnectorInfo;
import org.fluxtream.core.domain.Notification;
import org.fluxtream.core.domain.SharedConnector;
import org.fluxtream.core.domain.UpdateWorkerTask;
import org.fluxtream.core.domain.UpdateWorkerTask.Status;
import org.fluxtream.core.services.ApiDataService;
import org.fluxtream.core.services.CoachingService;
import org.fluxtream.core.services.ConnectorUpdateService;
import org.fluxtream.core.services.GuestService;
import org.fluxtream.core.services.NotificationsService;
import org.fluxtream.core.services.SettingsService;
import org.fluxtream.core.services.SystemService;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static org.fluxtream.core.utils.Utils.stackTrace;

@Component
@Scope("prototype")
class UpdateWorker implements Runnable {

	FlxLogger logger = FlxLogger.getLogger(UpdateWorker.class);

    @Autowired
	ConnectorUpdateService connectorUpdateService;

    @Autowired
	ApiDataService apiDataService;

	@Autowired
	GuestService guestService;

    @Autowired
    SystemService systemService;

    @Autowired
    NotificationsService notificationsService;

    @Autowired
    SettingsService settingsService;

    @Autowired
    CoachingService coachingService;

	@Autowired
	Configuration env;

	UpdateWorkerTask task;

	public UpdateWorker() {
	}

    @Trace(dispatcher=true)
	@Override
	public void run() {
        final boolean claimed = connectorUpdateService.claimForExecution(task.getId(), Thread.currentThread().getName());
        if (!claimed)
            return;
        logNR();
        StringBuilder sb = new StringBuilder("module=updateQueue component=worker action=start")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes)
                .append(" apiKeyId=").append(task.apiKeyId);
		logger.info(sb.toString());

		ApiKey apiKey = guestService.getApiKey(task.apiKeyId);
        Connector conn = apiKey.getConnector();

        // Check if this connector type is enabled and supportsSync before calling update.
        // If it is disabled and/or does not support sync, don't try to update it.
        boolean doUpdate = true;

        if (conn!=null) {
            try {
                final ConnectorInfo connectorInfo = systemService.getConnectorInfo(apiKey.getConnector().getName());
                // Make sure that this connector type supports sync and is enabled in this Fluxtream instance
                if (!connectorInfo.supportsSync || !connectorInfo.enabled) {
                    doUpdate = false;
                }
            }
            catch (Throwable e) {
                // Skip this connector
                doUpdate = false;
            }
        }
        else {
            doUpdate=false;
        }

        if(doUpdate) {
            AbstractUpdater updater = connectorUpdateService.getUpdater(conn);
            guestService.setApiKeyToSynching(apiKey.getId(), true);
            switch (task.updateType) {
                case INITIAL_HISTORY_UPDATE:
                    updateDataHistory(apiKey, updater);
                    break;
                case PUSH_TRIGGERED_UPDATE:
                    pushTriggeredUpdate(apiKey, updater);
                    break;
                case INCREMENTAL_UPDATE:
                    updateData(apiKey, updater);
                    break;
                default:
                    logger.warn("module=updateQueue component=worker message=\"UpdateType was not handled (" + task.updateType + ")\"");
                    connectorUpdateService.setUpdateWorkerTaskStatus(task.getId(), Status.FAILED);
            }
        }
        else {
            // This connector does not support update so mark the update task as done
            connectorUpdateService.setUpdateWorkerTaskStatus(task.getId(), Status.DONE);

            StringBuilder sb2 = new StringBuilder("module=updateQueue component=worker")
                    .append(" guestId=").append(task.getGuestId())
                    .append(" connector=").append(task.connectorName)
                    .append(" apiKeyId=").append(task.apiKeyId)
                    .append(" message=\"Connector does not support sync, skipping update\"");
    		logger.info(sb2.toString());
        }
	}

    private void logNR() {
        try {
            StringBuilder taskName = new StringBuilder("Background_Update_");
            taskName.append(task.connectorName);
            taskName.append("_").append(task.objectTypes);
            NewRelic.setTransactionName(null, taskName.toString());
            NewRelic.addCustomParameter("connector", task.connectorName);
            NewRelic.addCustomParameter("objectType", task.objectTypes);
            NewRelic.addCustomParameter("guestId", task.getGuestId());
        } catch (Throwable t) {
            logger.warn("Could not set NR info..." + task.connectorName);
        }
    }

    private void pushTriggeredUpdate(ApiKey apiKey,
			AbstractUpdater updater) {
		try {
            // TODO: check if this connector type is enabled and supportsSync before calling update.
            // If it is disabled and/or does not support sync, don't try to update it.

            logger.info("module=updateQueue component=worker action=pushTriggeredUpdate " +
                        "connector=" + apiKey.getConnector().getName() + " guestId=" + apiKey.getGuestId());
			UpdateInfo updateInfo = UpdateInfo.pushTriggeredUpdateInfo(apiKey,
					task.objectTypes, task.jsonParams);
			UpdateResult updateResult = updater.updateData(updateInfo);
			handleUpdateResult(updateInfo, updateResult);
		} catch (Throwable e) {
			String stackTrace = stackTrace(e);
			logger.warn("module=updateQueue component=worker action=pushTriggeredUpdate " +
                        "message=\"Unexpected Exception\" " +
                        "guestId=" + task.guestId + " objectType=" + task.objectTypes +
                        " stackTrace=<![CDATA[" + stackTrace + "]]>");
		}
	}

	private void updateDataHistory(ApiKey apiKey,
			AbstractUpdater updater) {
        // TODO: this message should not be displayed when this is called over and over as a result of rate limitations...
        String message = "<img class=\"loading-animation\" src=\"/static/img/loading.gif\"/>You have successfully added a new connector: "
                         + apiKey.getConnector().prettyName()
                         + ". Your data is now being retrieved. "
                         + "It may take a little while until it becomes visible.";
        notificationsService.addNamedNotification(apiKey.getGuestId(), Notification.Type.INFO, apiKey.getConnector().statusNotificationName(), message);
        // TODO: check if this connector type is enabled and supportsSync before calling update.
        // If it is disabled and/or does not support sync, don't try to update it.
        logger.info("module=updateQueue component=worker action=updateDataHistory " +
                    "connector=" + apiKey.getConnector().getName() + " guestId=" + apiKey.getGuestId());
        UpdateInfo updateInfo = UpdateInfo.initialHistoryUpdateInfo(apiKey,
                task.objectTypes);
        UpdateResult updateResult = updater.updateDataHistory(updateInfo);
        syncSettings(updater, updateInfo, updateResult);
        handleUpdateResult(updateInfo, updateResult);
	}

    private void updateData(final ApiKey apiKey, final AbstractUpdater updater) {
        // TODO: check if this connector type is enabled and supportsSync before calling update.
        // If it is disabled and/or does not support sync, don't try to update it.
        logger.info("module=updateQueue component=worker action=\"updateData (incremental update)\"" +
                    " connector=" + apiKey.getConnector().getName() + " guestId=" + apiKey.getGuestId());
        UpdateInfo updateInfo = UpdateInfo.IncrementalUpdateInfo(apiKey, task.objectTypes);
        UpdateResult result = updater.updateData(updateInfo);
        syncSettings(updater, updateInfo, result);
        handleUpdateResult(updateInfo, result);
    }

    private void syncSettings(final AbstractUpdater updater, final UpdateInfo updateInfo, final UpdateResult updateResult) {
        if (updateResult.getType()== UpdateResult.ResultType.UPDATE_SUCCEEDED) {
            if (updater instanceof SettingsAwareUpdater) {
                final SettingsAwareUpdater settingsAwareUpdater = (SettingsAwareUpdater)updater;
                syncConnectorSettings(updateInfo, settingsAwareUpdater);
            }
            if (updater instanceof SharedConnectorSettingsAwareUpdater) {
                final SharedConnectorSettingsAwareUpdater sharedConnectorSettingsAwareUpdater = (SharedConnectorSettingsAwareUpdater)updater;
                final ApiKey apiKey = guestService.getApiKey(updateInfo.apiKey.getId());
                final List<SharedConnector> sharedConnectors = coachingService.getSharedConnectors(apiKey);
                for (SharedConnector sharedConnector : sharedConnectors) {
                    sharedConnectorSettingsAwareUpdater.syncSharedConnectorSettings(updateInfo.apiKey.getId(), sharedConnector);
                }
            }
        }
    }

    private void syncConnectorSettings(final UpdateInfo updateInfo, final SettingsAwareUpdater settingsAwareUpdater) {
        final Object synchedSettings = settingsAwareUpdater.syncConnectorSettings(updateInfo, settingsService.getConnectorSettings(updateInfo.apiKey.getId()));
        final Object defaultSettings = settingsAwareUpdater.syncConnectorSettings(updateInfo, null);
        settingsService.persistConnectorSettings(updateInfo.apiKey.getId(), synchedSettings, defaultSettings);
    }

	private void handleUpdateResult(final UpdateInfo updateInfo, UpdateResult updateResult) {
        guestService.setApiKeyToSynching(updateInfo.apiKey.getId(), false);
        final Connector connector = updateInfo.apiKey.getConnector();
        String statusName = updateInfo.apiKey.getConnector().statusNotificationName();
        long guestId=updateInfo.apiKey.getGuestId();
        Notification notification = null;
        switch (updateResult.getType()) {
		case DUPLICATE_UPDATE:
			duplicateUpdate();
			break;
        case NEEDS_REAUTH:
            notificationsService.addNamedNotification(updateInfo.getGuestId(), Notification.Type.WARNING,
                                                      connector.statusNotificationName(),
                                                      "Heads Up. Your " + connector.prettyName() + " Authorization Token has expired.<br>" +
                                                      "Please head to <a href=\"javascript:App.manageConnectors()\">Manage Connectors</a>,<br>" +
                                                      "scroll to the " + connector.prettyName() + " section, and renew your tokens (look for the <i class=\"icon-resize-small icon-large\"></i> icon)");
            // ideally we would have an ApiKey.Status specifically for this but STATUS_OVER_RATE_LIMIT is good enough
            // especially with the accompanying message
            guestService.setApiKeyStatus(updateInfo.apiKey.getId(), ApiKey.Status.STATUS_OVER_RATE_LIMIT, connector.getName() + " needs re-auth");
            break;
		case HAS_REACHED_RATE_LIMIT:
            final UpdateWorkerTask.AuditTrailEntry rateLimit = new UpdateWorkerTask.AuditTrailEntry(new Date(), updateResult.getType().toString(), "long reschedule");
            rateLimit.stackTrace = updateResult.stackTrace;
            // do this only if a notification is visible for that connector at this time
            notification = notificationsService.getNamedNotification(guestId,statusName);
            if (notification!=null && notification.deleted==false) {
                notificationsService.addNamedNotification(guestId, Notification.Type.INFO,
                                                          statusName,
                                                          "<i class=\"icon-time\" style=\"margin-right:7px\"/>Import of your " + updateInfo.apiKey.getConnector().getPrettyName() + " data is delayed due to API rate limitations. Please, be patient.");
            }
			rescheduleAccordingToQuotaSpecifications(updateInfo, rateLimit);
			break;
		case UPDATE_SUCCEEDED:
            // Check for existing status notification
            notification = notificationsService.getNamedNotification(guestId,statusName);
            if (notification!=null && notification.deleted==false) {
                // This is either an initial history update or there's an existing visible status notification.
                // Update the notification to show the update succeeded.
                notificationsService.addNamedNotification(guestId, Notification.Type.INFO,
                                                          statusName,
                                                          "<i class=\"icon-ok\" style=\"margin-right:7px\"/>Your " + updateInfo.apiKey.getConnector().getPrettyName() + " data was successfully imported.  " +
                                                          "See <a href=\"javascript:App.manageConnectors()\">Manage Connectors</a> dialog for details.");
            }
			success(updateInfo.apiKey);
			break;
		case UPDATE_FAILED:
        case UPDATE_FAILED_PERMANENTLY:
            final UpdateWorkerTask.AuditTrailEntry failed = new UpdateWorkerTask.AuditTrailEntry(new Date(), updateResult.getType().toString(), "abort");
            failed.stackTrace = updateResult.stackTrace;
            connectorUpdateService.addAuditTrail(task.getId(), failed);
            final UpdateWorkerTask.AuditTrailEntry retry = new UpdateWorkerTask.AuditTrailEntry(new Date(), updateResult.getType().toString(), "retry");
            retry.stackTrace = updateResult.stackTrace;
            // Consider this a transient failure and retry if the failure type was not permanent
            // and the current status of the connector instance is not already STATUS_PERMANENT_FAILURE.
            //
            if(updateResult.getType()==UpdateResult.ResultType.UPDATE_FAILED &&
               updateInfo.apiKey.getStatus()!=ApiKey.Status.STATUS_PERMANENT_FAILURE) {
                retry(updateInfo, retry);
            }
            else {
                // This was a permanent failure, so we should set status to permanent failure and
                // we should not retry
                abort(updateInfo.apiKey,failed);
            }
            if (updateInfo.getUpdateType()== UpdateInfo.UpdateType.INITIAL_HISTORY_UPDATE)
                notificationsService.addNamedNotification(updateInfo.apiKey.getGuestId(), Notification.Type.ERROR,
                                                          connector.statusNotificationName(),
                                                          "<i class=\"icon-remove-sign\" style=\"color:red;margin-right:7px\"/>There was a problem while importing your " + connector.getPrettyName() + " data. We will try again later.  " +
                                                          "See <a href=\"javascript:App.manageConnectors()\">Manage Connectors</a> dialog for details."
                );
			break;
		case NO_RESULT:
			abort(updateInfo.apiKey,null);
			break;
		}
	}

    private void rescheduleAccordingToQuotaSpecifications(final UpdateInfo updateInfo, final UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
        longReschedule(updateInfo, auditTrailEntry);
        guestService.setApiKeyStatus(updateInfo.apiKey.getId(), ApiKey.Status.STATUS_OVER_RATE_LIMIT, auditTrailEntry.stackTrace);
    }

    private void duplicateUpdate() {
		logger.warn("module=updateQueue component=worker action=duplicateUpdate guestId=" + task.getGuestId() +
				" connector=" + task.connectorName + " objectType="
                + task.objectTypes);
	}

	private void success(ApiKey apiKey) {
		StringBuilder stringBuilder = new StringBuilder("module=updateQueue component=worker action=success")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.objectTypes);
		logger.info(stringBuilder.toString());
        guestService.setApiKeyStatus(apiKey.getId(), ApiKey.Status.STATUS_UP, null);
		connectorUpdateService.setUpdateWorkerTaskStatus(task.getId(), Status.DONE);
	}

	private void abort(ApiKey apiKey, UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
		StringBuilder stringBuilder = new StringBuilder("module=updateQueue component=worker action=abort")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes);
		logger.info(stringBuilder.toString());
        guestService.setApiKeyStatus(apiKey.getId(), ApiKey.Status.STATUS_PERMANENT_FAILURE, auditTrailEntry.stackTrace);
		connectorUpdateService.setUpdateWorkerTaskStatus(task.getId(), Status.FAILED);
	}

	private void retry(UpdateInfo updateInfo, UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
		StringBuilder stringBuilder = new StringBuilder("module=updateQueue component=worker action=retry")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes);
        if (auditTrailEntry.stackTrace!=null) {
            stringBuilder.append(" stackTrace=<![CDATA[")
                    .append(auditTrailEntry.stackTrace)
                    .append("]]>");
        }
		logger.info(stringBuilder.toString());
		int maxRetries = 0;
		try {
			maxRetries = getMaxRetries(updateInfo.apiKey.getConnector());
		} catch (Throwable t) {
			t.printStackTrace();
		}
		if (task.retries < maxRetries) {
            auditTrailEntry.nextAction = "short reschedule";
            shortReschedule(updateInfo.apiKey, auditTrailEntry);
		} else {
            auditTrailEntry.nextAction = "long reschedule";
 			longReschedule(updateInfo, auditTrailEntry);
		}
	}

	private void longReschedule(UpdateInfo updateInfo, UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
		StringBuilder stringBuilder = new StringBuilder("module=updateQueue component=worker action=longReschedule")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes);
		logger.info(stringBuilder.toString());
        guestService.setApiKeyStatus(updateInfo.apiKey.getId(), ApiKey.Status.STATUS_TRANSIENT_FAILURE, auditTrailEntry.stackTrace);
		// re-schedule when we are below rate limit again
        Long resetTime = updateInfo.getSafeResetTime();
        if (resetTime==null) {
            final int longRetryDelay = getLongRetryDelay(updateInfo.apiKey.getConnector());
            resetTime = System.currentTimeMillis() + longRetryDelay;
        }
        connectorUpdateService.reScheduleUpdateTask(task.getId(), resetTime,
                                                    false, auditTrailEntry);
	}

	private void shortReschedule(ApiKey apiKey, UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
		StringBuilder sb = new StringBuilder("module=updateQueue component=worker action=shortReschedule")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes)
                .append(" retries=").append(String.valueOf(task.retries));
		logger.info(sb.toString());
		// schedule 1 minute later, typically
        guestService.setApiKeyStatus(apiKey.getId(), ApiKey.Status.STATUS_TRANSIENT_FAILURE, auditTrailEntry.stackTrace);
		connectorUpdateService.reScheduleUpdateTask(task.getId(), System.currentTimeMillis() + getShortRetryDelay(apiKey.getConnector()),
                                                    true, auditTrailEntry);
	}

	private int getMaxRetries(Connector connector) {
		String key = connector.getName() + ".maxRetries";
        if (env.connectors.containsKey(key)) {
            return Integer.valueOf((String)env.connectors.getProperty(key));
        }
        else {
            return Integer.valueOf((String)env.connectors.getProperty("maxRetries"));
        }
	}

	private int getShortRetryDelay(Connector connector) {
		String key = connector.getName() + ".shortRetryDelay";
        if (env.connectors.containsKey(key)) {
            return Integer.valueOf((String)env.connectors.getProperty(key));
        }
        else {
            return Integer.valueOf((String)env.connectors.getProperty("shortRetryDelay"));
        }
	}

	private int getLongRetryDelay(Connector connector) {
		String key = connector.getName() + ".longRetryDelay";
        if (env.connectors.containsKey(key)) {
            return Integer.valueOf((String)env.connectors.getProperty(key));
        }
        else {
            return Integer.valueOf((String)env.connectors.getProperty("longRetryDelay"));
        }
	}

}