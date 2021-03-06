/*
 *
 *  *
 *  * SecureCodeBox (SCB)
 *  * Copyright 2015-2018 iteratec GmbH
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * 	http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package io.securecodebox.zap.jobs;

import de.otto.edison.jobs.service.JobService;
import io.securecodebox.zap.jobs.definition.EngineWorkerJob;
import io.securecodebox.zap.service.engine.ZapTaskService;
import io.securecodebox.zap.togglz.ZapFeature;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;


@Component
@Slf4j
@ToString
public class JobScheduler {
    private static final DateTimeFormatter dateFormatter = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter(Locale.ENGLISH);

    private final JobService jobService;
    private final ZapTaskService taskService;


    @Autowired
    public JobScheduler(JobService jobService, ZapTaskService taskService) {
        this.jobService = jobService;
        this.taskService = taskService;
    }


    /**
     * Starts the {@link EngineWorkerJob} every minute if {@link ZapFeature#DISABLE_TRIGGER_ALL_JOBS} is inactive.
     */
    @Scheduled(cron = "${securecodebox.zap.jobsSchedulerCron}")
    public void scheduleEngineWorkerJob() {
        String now = dateFormatter.format(LocalDateTime.now());
        if (ZapFeature.DISABLE_TRIGGER_ALL_JOBS.isActive()) {
            log.debug("The Job trigger time is inactive: {}", now);
        } else {
            log.debug("The Job trigger time is active: {}", now);
            jobService.startAsyncJob(EngineWorkerJob.JOB_TYPE);
        }
    }
}
