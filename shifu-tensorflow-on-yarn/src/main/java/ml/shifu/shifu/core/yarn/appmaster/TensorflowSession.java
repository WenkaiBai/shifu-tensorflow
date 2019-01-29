/*
 * Copyright [2013-2018] PayPal Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu.core.yarn.appmaster;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Preconditions;

import ml.shifu.guagua.coordinator.zk.GuaguaZooKeeper;
import ml.shifu.guagua.coordinator.zk.ZooKeeperUtils;
import ml.shifu.shifu.core.yarn.util.CommonUtils;
import ml.shifu.shifu.core.yarn.util.Constants;
import ml.shifu.shifu.core.yarn.util.GlobalConfigurationKeys;

/**
 * @author webai
 * 
 *         Tensorflow Session contains all tensorflow jobs information
 */
public class TensorflowSession implements Watcher {
    private static final Log LOG = LogFactory.getLog(TensorflowSession.class);
    private Configuration globalConf;

    private Map<String, TensorFlowContainerRequest> containerRequests;
    private Map<String, List<ContainerRequest>> jobNameToContainerRequests = new HashMap<String, List<ContainerRequest>>();
    private Map<String, TensorflowTask> containerIdToTask = new HashMap<String, TensorflowTask>();
    // A map from task name to an array of TFTasks with that name.
    private Map<String, TensorflowTask[]> jobNameToTasks = new ConcurrentHashMap<String, TensorflowTask[]>();
    private Map<String, ConcurrentLinkedQueue<TensorflowTask>> jobNameToBackupTask = 
            new ConcurrentHashMap<String, ConcurrentLinkedQueue<TensorflowTask>>();

    private TensorflowClusterSpec tensorflowClusterSpec;

    /** those task not have container **/
    private Map<String, Integer> jobNameToPendingTaskNumber = new ConcurrentHashMap<String, Integer>();
    private Map<String, Integer> jobNameToPendingBackupTaskNumber = new ConcurrentHashMap<String, Integer>();
    private int numRequestedContainers = 0;

    /** train data set **/
    private List<StringBuilder> splitedTrainingData = null;

    /** Job progress **/
    private AtomicInteger numCompletedWorkerTasks = new AtomicInteger(0);
    private Map<String, Integer> jobNameToTaskNum = new ConcurrentHashMap<String, Integer>();
    
    /** failed task index in task array including timeout task and return wrong exit code **/
    private ConcurrentLinkedQueue<Integer> failedWorkers = new ConcurrentLinkedQueue<Integer>();
    /** failed only when return wrong exit code **/
    private ConcurrentLinkedQueue<Integer> failedPs = new ConcurrentLinkedQueue<Integer>();    

    // sessionId to distinguish different sessions. Currently used to distinguish
    // failed session and new session.
    public static int sessionId = 0;

    private FinalApplicationStatus sessionFinalStatus = FinalApplicationStatus.UNDEFINED;
    private String sessionFinalMessage = null;

    // if Chief worker finished with non-zero exit code, we stop whole training
    private boolean chiefWorkerSuccess = true;

    private static String zookeeperServerHostPort = null;
    private static GuaguaZooKeeper zookeeperServer = null;

    public enum TaskType {
        TASK_TYPE_CHIEF, TASK_TYPE_PARAMETER_SERVER, TASK_TYPE_WORKER
    }
    public TensorflowSession() {}
    public TensorflowSession(Configuration globalConf) {
        this.globalConf = globalConf;
        this.containerRequests = CommonUtils.parseContainerRequests(this.globalConf);

        // create zookeeper server for sync tensorflow cluster spec
        // This has been settled in prepare of AM
        if (zookeeperServer == null) {
            zookeeperServerHostPort = startZookeeperServer();
            try {
                zookeeperServer = new GuaguaZooKeeper(zookeeperServerHostPort, 300000, 5, 1000, this);
            } catch (IOException e) {
                LOG.error("create zookeeper server fails!", e);
                throw new RuntimeException(e);
            }
        }

        Map<String, Integer> jobNameToBackupTaskNum = new HashMap<String, Integer>();
        for(String jobName: containerRequests.keySet()) {
            int taskCnt = containerRequests.get(jobName).getNumInstances();
            int backupTaskCnt = containerRequests.get(jobName).getNumBackupInstances();
            
            jobNameToTasks.put(jobName, new TensorflowTask[taskCnt]);
            jobNameToBackupTask.put(jobName,  new ConcurrentLinkedQueue<TensorflowTask>());
            
            jobNameToTaskNum.put(jobName, taskCnt);
            jobNameToBackupTaskNum.put(jobName, backupTaskCnt);
        }

        this.tensorflowClusterSpec = new TensorflowClusterSpec(
                jobNameToTaskNum.get(Constants.PS_JOB_NAME) + jobNameToBackupTaskNum.get(Constants.PS_JOB_NAME), 
                jobNameToTaskNum.get(Constants.WORKER_JOB_NAME) + jobNameToBackupTaskNum.get(Constants.WORKER_JOB_NAME));
        
        // Split training data for workers
        try {
            splitedTrainingData = TrainingDataSet.getInstance().getSplitedFilePaths(this.globalConf, jobNameToTaskNum.get(Constants.WORKER_JOB_NAME),
                    this.globalConf.get(GlobalConfigurationKeys.TRAINING_DATA_PATH));
            LOG.info("splitedTrainingData: " + splitedTrainingData.toString());
        } catch (Exception e) {
            LOG.error("Splitting training data fails!", e);
            throw new RuntimeException(e);
        }
    }

    private static String startZookeeperServer() {
        String localHostName = CommonUtils.getCurrentHostName();
        
        int embedZkClientPort = 0;
        try {
            embedZkClientPort = ZooKeeperUtils.startEmbedZooKeeper();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // 2. check if it is started.
        ZooKeeperUtils.checkIfEmbedZooKeeperStarted(embedZkClientPort);
        return localHostName + ":" + embedZkClientPort;
    }
    
    public void scheduleTasks(AMRMClientAsync<ContainerRequest> amRMClient) {
        for(String jobName: containerRequests.keySet()) {
            if(!jobNameToContainerRequests.containsKey(jobName)) {
                jobNameToContainerRequests.put(jobName, new ArrayList<ContainerRequest>());
            }

            TensorFlowContainerRequest containerRequest = containerRequests.get(jobName);
            // prepare resource request and add to amRMClient
            for(int i = 0; i < (containerRequest.getNumInstances() + containerRequest.getNumBackupInstances()); i++) {
                AMRMClient.ContainerRequest containerAsk = setupContainerRequestForRM(containerRequest);

                jobNameToContainerRequests.get(jobName).add(containerAsk);

                amRMClient.addContainerRequest(containerAsk);

                numRequestedContainers++;
            }

            jobNameToPendingTaskNumber.put(jobName, containerRequest.getNumInstances());
            jobNameToPendingBackupTaskNumber.put(jobName, containerRequest.getNumBackupInstances());
        }
    }

    private AMRMClient.ContainerRequest setupContainerRequestForRM(TensorFlowContainerRequest request) {
        Priority priority = Priority.newInstance(request.getPriority());
        Resource capability = Resource.newInstance((int) request.getMemory(), request.getVCores());
        AMRMClient.ContainerRequest containerRequest = new AMRMClient.ContainerRequest(capability, null, null,
                priority);
        LOG.info("Requested container ask: " + containerRequest.toString());
        return containerRequest;
    }

    /**
     * @param container
     * @return return null means error on pending task number
     */
    public synchronized TensorflowTask distributeTaskToContainer(Container container) {
        String jobName = getAvailableJobName(container);
        
        if (StringUtils.isBlank(jobName)) {
            LOG.error("Cannot distribute container: " + container.toString());
            throw new RuntimeException("couldn't find job to match container");
        } else {
            try {
                zookeeperServer.exists(Constants.TENSORFLOW_CLUSTER_ROOT_PATH + container.getId().toString(), this);
            } catch (Exception e) {
                LOG.error("watch container fails", e);
                throw new RuntimeException(e);
            }
            
            if (jobNameToPendingTaskNumber.get(jobName) > 0) {
                // distribute container to task
                TensorflowTask[] tasks = jobNameToTasks.get(jobName);
                for(int i = 0; i < tasks.length; i++) {
                    if(tasks[i] == null) {
                        tasks[i] = new TensorflowTask(jobName, String.valueOf(i), sessionId, container,
                                splitedTrainingData.get(i).toString(), zookeeperServerHostPort, 
                                globalConf, false, i);

                        jobNameToPendingTaskNumber.put(jobName, jobNameToPendingTaskNumber.get(jobName) - 1);
                        containerIdToTask.put(container.getId().toString(), tasks[i]);
                        return tasks[i];
                    }
                }
            } else if (jobNameToPendingBackupTaskNumber.get(jobName) > 0) {
                // distribute container to backup task
                int taskId = jobNameToTaskNum.get(jobName) + jobNameToBackupTask.get(jobName).size();
                TensorflowTask task = new TensorflowTask(jobName, String.valueOf(taskId), sessionId, container,
                        null, zookeeperServerHostPort, globalConf, true, -1);

                jobNameToBackupTask.get(jobName).offer(task);
                jobNameToPendingBackupTaskNumber.put(jobName, jobNameToPendingBackupTaskNumber.get(jobName) - 1);
                containerIdToTask.put(container.getId().toString(), task);
                return task;
            }
        }
           
        throw new RuntimeException("Error when distribute container to task");
    }

    /**
     * Available job need two requirements: 1. container resource(mem, core) is same as job request resource
     * 2. job task is not full
     * 
     * @param container
     * @return
     */
    private synchronized String getAvailableJobName(Container container) {
        LOG.info("allocated resource: " + container.getResource().toString());
        LOG.info("remaining resource: " +jobNameToPendingTaskNumber);
        LOG.info("session id: " + sessionId);
        for(Map.Entry<String, TensorFlowContainerRequest> jobNameToRequest: containerRequests.entrySet()) {
            String jobName = jobNameToRequest.getKey();
            TensorFlowContainerRequest request = jobNameToRequest.getValue();
            int pendingNumber = jobNameToPendingTaskNumber.get(jobName);
            int pendingBackupNumber = jobNameToPendingBackupTaskNumber.get(jobName);
            
            if((int) request.getMemory() == container.getResource().getMemory()
                    && request.getVCores() == container.getResource().getVirtualCores() 
                    && (pendingNumber > 0 || pendingBackupNumber > 0)) {
                return jobName;
            }
        }

        return null;
    }

    public TensorflowTask getTaskByContainerId(ContainerId containerId) {
        return this.containerIdToTask.get(containerId.toString());
    }

    public TensorflowTask getTaskFromNormalTasks(String jobName, String taskIndex) {
        for(Map.Entry<String, TensorflowTask[]> entry: this.jobNameToTasks.entrySet()) {
            TensorflowTask[] tasks = entry.getValue();
            for(TensorflowTask task: tasks) {
                if(task.getJobName().equals(jobName) && task.getTaskIndex().equals(taskIndex)) {
                    return task;
                }
            }
        }

        return null;
    }
    
    public TensorflowTask getTaskFromBackupTasks(String jobName, String taskIndex) {
        Iterator<TensorflowTask> backupItr = jobNameToBackupTask.get(jobName).iterator();
        while(backupItr.hasNext()) {
            TensorflowTask task = backupItr.next();
            if(task.getJobName().equals(jobName) && task.getTaskIndex().equals(taskIndex)) {
                return task;
            }
        }
        return null;
    }

    private boolean isChief(String jobName, String jobIndex) {
        String chiefName = Constants.WORKER_JOB_NAME;
        String chiefIndex = "0";
        return jobName.equals(chiefName) && jobIndex.equals(chiefIndex);
    }

    public void setFinalStatus(FinalApplicationStatus status, String message) {
        sessionFinalStatus = status;
        sessionFinalMessage = message;
    }

    private int getFailedTasksNum(TensorflowTask[] tasks) {
        int failedTaskNum = 0;
        for (TensorflowTask task: tasks) {
            if(task == null) {
                String msg = "Job is null, this should not happen.";
                LOG.error(msg);
                setFinalStatus(FinalApplicationStatus.FAILED, msg);
                return 0;
            }
            if (task.exitStatus != 0) {
                failedTaskNum += 1;
                String msg = "Job " + task.getJobName() + " at index: " + task.getTaskIndex()
                    + " haven't finished yet.";
                LOG.error(msg);
            }
        }
        return failedTaskNum;
    }
    
    /**
     * To get max number of ps could failed and training process does not have impact
     * @return
     */
    public double failedPsMaxLimit() {
        return Constants.PS_FAULT_TOLERNANCE_THREAHOLD * getNumTotalPsTasks() + 
                getJobNameToBackupTask().get(Constants.PS_JOB_NAME).size();
    }
    
    /**
     * To get max number of worker could failed and training process does not have impact
     * @return
     */
    public double failedWorkerMaxLimit() {
        return Constants.WORKER_FAULT_TOLERENANCE_THRESHOLD * getNumTotalWorkerTasks() + 
                getJobNameToBackupTask().get(Constants.WORKER_JOB_NAME).size();
    }
    
    /**
     * Update the status of a session and set exit code if a session is completed.
     */
    public void updateSessionStatus() {
        if(getFinalStatus() == FinalApplicationStatus.FAILED) {
            return;
        }
        
        int failedWorkerNum = getFailedTasksNum(jobNameToTasks.get(Constants.WORKER_JOB_NAME));
        int failedPsNum = getFailedTasksNum(jobNameToTasks.get(Constants.PS_JOB_NAME));

        if(failedPsNum >= failedPsMaxLimit()) {
            setFinalStatus(FinalApplicationStatus.FAILED, "There is no PS sucess, failedCnt=" + failedPsNum);
        } else if(failedWorkerNum >= failedWorkerMaxLimit()) {
            setFinalStatus(FinalApplicationStatus.FAILED,
                    "More than threshold of worker failed, failedCnt=" + failedWorkerNum);
        } else if(!chiefWorkerSuccess) {
            setFinalStatus(FinalApplicationStatus.FAILED, "Chief worker failed");
        } else {
            LOG.info("Session completed with no job failures, setting final status SUCCEEDED.");
            setFinalStatus(FinalApplicationStatus.SUCCEEDED, null);
        }
    }

    
    
    /**
     * Refresh task status on each TaskExecutor registers its exit code with AM.
     */
    public void onTaskCompleted(String jobName, String jobIndex, int exitCode) {
        LOG.info(String.format("Job %s:%s exited with %d", jobName, jobIndex, exitCode));
        TensorflowTask backupTask = getTaskFromBackupTasks(jobName, jobIndex);
        if (backupTask != null) {
            // if backup task fails, we just remove it from standing-by queue
            // do not need to worry
            jobNameToBackupTask.get(jobName).remove(backupTask);
            LOG.error("backup task fails!!");
            return;
        }
        
        TensorflowTask task = getTaskFromNormalTasks(jobName, jobIndex);
        Preconditions.checkNotNull(task);
        
        // mark current task as completed
        task.setExitStatus(exitCode);
        
        if (Constants.WORKER_JOB_NAME.equals(jobName)) {
            if (exitCode == 0) {
                // success
                numCompletedWorkerTasks.incrementAndGet();
            } else {
                if (isChief(jobName, jobIndex)) {
                    // If the chief worker failed[chief or worker 0], short circuit and stop the training. Note that even
                    // though other worker failures will also fail the job but we don't short circuit the training because
                    // the training
                    // can still continue, while if chief worker is dead, a TensorFlow training would hang.
                    // Also note that, we only short circuit when the chief worker failed, not finished.
                    chiefWorkerSuccess = false;
                } 
                failedWorkers.offer(task.getArrayIndex());
            }
        } else {
            if (exitCode != 0) {
                // ps fails
                failedPs.offer(task.getArrayIndex());
            }
        }
        
    }

    public void stopAllTasks(NMClientAsync nmClientAsync) {
        for(TensorflowTask task: this.containerIdToTask.values()) {
            if(task != null) {
                nmClientAsync.stopContainerAsync(task.getContainer().getId(), task.getContainer().getNodeId());
                LOG.info("Stop a task in container: containerId = " + task.getContainer().getId() + ", containerNode = "
                        + task.getContainer().getNodeId().getHost());
            }
        }
    }

    public AtomicInteger getNumCompletedWorkerTasks() {
        return numCompletedWorkerTasks;
    }

    public void setNumCompletedWorkerTasks(AtomicInteger numCompletedWorkerTasks) {
        this.numCompletedWorkerTasks = numCompletedWorkerTasks;
    }

    public int getNumTotalWorkerTasks() {
        return jobNameToTaskNum.get(Constants.WORKER_JOB_NAME);
    }
    
    public int getNumTotalPsTasks() {
        return jobNameToTaskNum.get(Constants.PS_JOB_NAME);
    }
    
    public ConcurrentLinkedQueue<Integer> getFailedWorkers() {
        return failedWorkers;
    }
    public ConcurrentLinkedQueue<Integer> getFailedPs() {
        return failedPs;
    }

    public void process(WatchedEvent event) {
        if (event == null || StringUtils.isBlank(event.getPath())) {
            return;
        }
        
        String containerId = event.getPath().replace(Constants.TENSORFLOW_CLUSTER_ROOT_PATH, "");
        try {
            String ipAndPort = new String(zookeeperServer.getData(event.getPath(), null, null));
            TensorflowTask task = this.containerIdToTask.get(containerId);
            tensorflowClusterSpec.add(task.getJobName(), Integer.valueOf(task.getTaskIndex()), ipAndPort);
        } catch (Exception e) {
            LOG.error("Getting worker port fails.", e);
            throw new RuntimeException(e);
        }

        int readyContainersNumber = tensorflowClusterSpec.totalWorkerAndPs();
        if(readyContainersNumber == numRequestedContainers) {
            LOG.info("Get all host and port from containers");
            try {
                zookeeperServer.createOrSetExt(Constants.TENSORFLOW_FINAL_CLUSTER,
                        tensorflowClusterSpec.toString().getBytes(Charset.forName("UTF-8")), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT, true, -1);
            } catch (Exception e) {
                LOG.fatal("Writing final cluster spec to zookeeper fails.", e);
                throw new RuntimeException(e);
            }
        } else if(readyContainersNumber < numRequestedContainers) {
            LOG.info("total: " + numRequestedContainers + ", ready: " + readyContainersNumber);
        } else {
            LOG.fatal("total: " + numRequestedContainers + ", ready: " + readyContainersNumber);
        }
    }

    public boolean isChiefWorkerSuccess() {
        return chiefWorkerSuccess;
    }

    public FinalApplicationStatus getFinalStatus() {
        return this.sessionFinalStatus;
    }

    public String getFinalMessage() {
        return this.sessionFinalMessage;
    }

    public Configuration getGlobalConf() {
        return this.globalConf;
    }
    
    public Map<String, TensorflowTask[]> getJobNameToTasks() {
        return jobNameToTasks;
    }
    
    public Map<String, ConcurrentLinkedQueue<TensorflowTask>> getJobNameToBackupTask() {
        return jobNameToBackupTask;
    }
    
    class TensorflowClusterSpec {
        // In order to make spec host order same as task order
        private String[] ps;
        private String[] worker;
        private int readyPsCnt = 0;
        private int readyWorkerCnt = 0;
        
        TensorflowClusterSpec(int psTaskCnt, int workerTaskCnt) {
            ps = new String[psTaskCnt];
            worker = new String[workerTaskCnt];
        }
        
        public synchronized void add(String jobName, int taskId, String hostnamePort) {
            if("ps".equalsIgnoreCase(jobName)) {
                ps[taskId] = hostnamePort;
                readyPsCnt += 1;
            } else {
                worker[taskId] = hostnamePort;
                readyWorkerCnt += 1;
            }
        }

        public String[] getPs() {
            return ps;
        }
        
        public String[] getWorker() {
            return worker;
        }
        
        public synchronized int totalWorkerAndPs() {
            return readyWorkerCnt + readyPsCnt;
        }
        
        public String toString() {
            ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
            try {
                return ow.writeValueAsString(this);
            } catch (JsonProcessingException e) {
                LOG.error("transfer cluster failed", e);
            }
            //return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
            return StringUtils.EMPTY;
        }
    }

    /**
     * @param backupWorkerTask
     * @param failedWorkerTaskArrayId
     * @throws InterruptedException 
     * @throws KeeperException 
     */
    public void weakupBackup(TensorflowTask backupWorkerTask, Integer failedWorkerTaskArrayId) throws KeeperException, InterruptedException {
        TensorflowTask[] workers = this.jobNameToTasks.get(Constants.WORKER_JOB_NAME);
        TensorflowTask failedWorkerTask = workers[failedWorkerTaskArrayId];
        backupWorkerTask.setTrainingDataPaths(failedWorkerTask.getTrainingDataPaths());
        
        // write data path into zookeeper so that to weak up backup task
        LOG.info("failedWorkerTask.getTrainingDataPaths(): " + failedWorkerTask.getTrainingDataPaths());
        zookeeperServer.createOrSetExt(Constants.getTrainingDataZookeeperPath(
                backupWorkerTask.getContainer().getId().toString()),
                failedWorkerTask.getTrainingDataPaths().getBytes(Charset.forName("UTF-8")), 
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT, true, -1);
        
        workers[failedWorkerTaskArrayId] = backupWorkerTask;
    }
}