/*
 * #%L
 * ACS AEM Commons Bundle
 * %%
 * Copyright (C) 2015 Adobe
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package com.adobe.acs.commons.workflow.bulk.removal.impl;

import com.adobe.acs.commons.workflow.bulk.removal.WorkflowInstanceRemover;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component(
        label = "ACS AEM Commons - Workflow Instance Remover - Scheduled Service",
        metatype = true,
        configurationFactory = true
)
@Properties({
        @Property(
                label = "Cron expression defining when this Scheduled Service will run",
                description = "[12:01am daily = 0 1 0 ? * *]; see www.cronmaker.com",
                name = "scheduler.expression",
                value = "0 1 0 ? * *"
        ),
        @Property(
                label = "Allow concurrent executions",
                description = "Allow concurrent executions of this Scheduled Service",
                name = "scheduler.concurrent",
                boolValue = false,
                propertyPrivate = true
        ),
})
@Service
public class WorkflowInstanceRemoverScheduler implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(WorkflowInstanceRemoverScheduler.class);

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private WorkflowInstanceRemover workflowInstanceRemover;


    private static final String[] DEFAULT_WORKFLOW_STATUSES = {"COMPLETE", "ABORTED"};

    private List<String> statuses = new ArrayList<String>();

    @Property(label = "Workflow Status",
            description = "Only remove Workflow Instances that have one of these statuses.",
            value = { "COMPLETE", "ABORTED" })
    public static final String PROP_WORKFLOW_STATUSES = "workflow.statuses";


    private static final String[] DEFAULT_WORKFLOW_MODELS = {};

    private List<String> models = new ArrayList<String>();

    @Property(label = "Workflow Models",
            description = "Only remove Workflow Instances that belong to one of these WF Models.",
            cardinality = Integer.MAX_VALUE,
            value = { })
    public static final String PROP_WORKFLOW_MODELS = "workflow.models";


    private static final String[] DEFAULT_WORKFLOW_PAYLOADS = {};

    private List<Pattern> payloads = new ArrayList<Pattern>();

    @Property(label = "Payload Patterns",
            description = "Only remove Workflow Instances whose payloads match one of these regex patterns",
            cardinality = Integer.MAX_VALUE,
            value = { })
    public static final String PROP_WORKFLOW_PAYLOADS = "workflow.payloads";


    private Calendar olderThan = null;

    @Property(label = "Older Than UTC TS",
            description = "Only remove Workflow Instances whose payloads are older than this UTC Time in Millis",
            longValue = 0)
    public static final String PROP_WORKFLOWS_OLDER_THAN = "workflow.older-than";


    private static final int DEFAULT_BATCH_SIZE = 1000;
    private int batchSize = DEFAULT_BATCH_SIZE;
    @Property(label = "Batch Size",
            description = "Save removals to JCR in batches of this defined size.",
            intValue = DEFAULT_BATCH_SIZE)
    public static final String PROP_BATCH_SIZE = "batch-size";
    
    @Override
    public final void run() {

        ResourceResolver adminResourceResolver = null;
        try {
            adminResourceResolver = resourceResolverFactory.getAdministrativeResourceResolver(null);

            final long start = System.currentTimeMillis();

            int count = workflowInstanceRemover.removeWorkflowInstances(
                    adminResourceResolver,
                    models,
                    statuses,
                    payloads,
                    olderThan, 
                    batchSize);

            log.info("Removed [ {} ] Workflow instances in {} ms", count, System.currentTimeMillis() - start);

        } catch (LoginException e) {
            log.error("Login Exception when getting admin resource resolver", e);
        } catch (PersistenceException e) {
            log.error("Persistence Exception when saving Workflow Instances removal", e);
        } catch (WorkflowRemovalException e) {
            log.error("Errors in persistence retries during Workflow Removal", e);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            if (adminResourceResolver != null) {
                adminResourceResolver.close();
            }
        }
    }

    @Activate
    protected final void activate(final Map<String, String> config) {
        statuses = Arrays.asList(PropertiesUtil.toStringArray(
                config.get(PROP_WORKFLOW_STATUSES), DEFAULT_WORKFLOW_STATUSES));

        models = Arrays.asList(PropertiesUtil.toStringArray(
                config.get(PROP_WORKFLOW_MODELS), DEFAULT_WORKFLOW_MODELS));

        final String[] payloadsArray =
                PropertiesUtil.toStringArray(config.get(PROP_WORKFLOW_PAYLOADS), DEFAULT_WORKFLOW_PAYLOADS);

        for (String payload : payloadsArray) {
            Pattern p = Pattern.compile(payload);
            if (p != null) {
                payloads.add(p);
            }
        }

        final Long olderThanTs = PropertiesUtil.toLong(config.get(PROP_WORKFLOWS_OLDER_THAN), 0);

        if (olderThanTs > 0) {
            olderThan = Calendar.getInstance();
            olderThan.setTimeInMillis(olderThanTs);
        }
        
        batchSize = PropertiesUtil.toInteger(config.get(PROP_BATCH_SIZE), DEFAULT_BATCH_SIZE);
        if (batchSize < 1) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
    }

    @Deactivate
    protected final void deactivate(final Map<String, String> config) {
        olderThan = null;
        statuses = new ArrayList<String>();
        models = new ArrayList<String>();
        payloads = new ArrayList<Pattern>();
    }
}
