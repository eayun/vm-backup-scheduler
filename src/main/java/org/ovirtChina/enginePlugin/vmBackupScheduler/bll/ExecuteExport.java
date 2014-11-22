package org.ovirtChina.enginePlugin.vmBackupScheduler.bll;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimerTask;

import org.apache.http.client.ClientProtocolException;
import org.ovirt.engine.sdk.Api;
import org.ovirt.engine.sdk.decorators.StorageDomain;
import org.ovirt.engine.sdk.decorators.VM;
import org.ovirt.engine.sdk.entities.Action;
import org.ovirt.engine.sdk.exceptions.ServerException;
import org.ovirtChina.enginePlugin.vmBackupScheduler.common.BackupMethod;
import org.ovirtChina.enginePlugin.vmBackupScheduler.common.Task;
import org.ovirtChina.enginePlugin.vmBackupScheduler.common.TaskStatus;
import org.ovirtChina.enginePlugin.vmBackupScheduler.common.TaskType;
import org.ovirtChina.enginePlugin.vmBackupScheduler.dao.DbFacade;
import org.ovirtChina.enginePlugin.vmBackupScheduler.utils.OVirtEngineSDKUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecuteExport extends TimerSDKTask {
    private static Logger log = LoggerFactory.getLogger(TimerTask.class);
    DateFormat df = new SimpleDateFormat("MM-dd-yyyy_HH-mm-ss");

    protected void peformAction() throws ClientProtocolException, ServerException, IOException, InterruptedException {
        Task taskToExec = DbFacade.getInstance().getTaskDAO().getOldestTaskTypeWithStatus(TaskType.CreateExport, TaskStatus.EXECUTING);
        if (taskToExec == null) {
            taskToExec = DbFacade.getInstance().getTaskDAO().getOldestTaskTypeWithStatus(TaskType.CreateExport, TaskStatus.WAITING);
        }
        if (taskToExec == null) {
            log.info("There is no export task to execute.");
            return;
        }
        api = OVirtEngineSDKUtils.getApi();
        if (api != null) {
            StorageDomain isoDoaminToExport = getIsoDomainToExport(api);
            if (isoDoaminToExport != null) {
                if (taskToExec.getTaskStatus() == TaskStatus.WAITING.getValue()) {
                    VM vm = api.getVMs().get(taskToExec.getVmID());
                    if (vm.getStatus().getState().equals("down")) {
                        VM vmCopy = copyVm(vm);
                        setTaskStatus(taskToExec, TaskStatus.EXECUTING);
                        try{
                            queryCopying(vmCopy);
                        } catch (Exception e) {
                            log.error("Error while copying vm: " + vmCopy.getName(), e);
                            setTaskStatus(taskToExec, TaskStatus.FAILED);
                            deleteVmCopy(vmCopy);
                            return;
                        }
                        Action action = new Action();
                        action.setStorageDomain(isoDoaminToExport);
                        log.info("Start executing task " + BackupMethod.forValue(taskToExec.getTaskType()) + " for vm: " + vmCopy.getName());
                        api.getVMs().get(vmCopy.getName()).exportVm(action);
                        try{
                            queryExport(api, taskToExec, vmCopy);
                        } catch (Exception e) {
                            log.error("Error while exporting vm: " + vmCopy.getName(), e);
                            setTaskStatus(taskToExec, TaskStatus.FAILED);
                            return;
                        } finally {
                            deleteVmCopy(vmCopy);
                        }
                        setTaskStatus(taskToExec, TaskStatus.FINISHED);
                        log.info("Execution of task" + BackupMethod.forValue(taskToExec.getTaskType()) + " for vm: " + vm.getName() + " succeeded.");
                    } else {
                        setTaskStatus(taskToExec, TaskStatus.FAILED);
                        log.warn("Exporting vm failed, vm: " + vm.getId() + " is not down.");
                    }

                }
            }
        }
    }

    private void queryCopying(VM vmCopy) throws ClientProtocolException, ServerException, IOException, InterruptedException {
        while(!api.getVMs().get(vmCopy.getName()).getStatus().getState().equals("down")) {
            log.info("vm: " + vmCopy.getName() + " is being cloned, waiting for next query...");
            Thread.sleep(5000);
        }
    }

    private void deleteVmCopy(VM vmCopy) throws ClientProtocolException, ServerException, IOException {
        api.getVMs().get(vmCopy.getName()).delete();
        log.info("vm: " + vmCopy.getName() + " has deleted.");
    }

    private VM copyVm(VM vm) throws ClientProtocolException, ServerException, IOException, InterruptedException {
        VM copyVm = new VM(null);
        String copyVmName = vm.getName() + "_Backup_" + df.format(new Date());
        copyVm.setName(copyVmName);
        Action action = new Action();
        action.setVm(copyVm);
        api.getVMs().getById(vm.getId()).clone(action);

        return copyVm;
    }

    private void queryExport(Api api, Task taskToExec, VM vm) throws ClientProtocolException, ServerException, IOException, InterruptedException {
        while (!api.getVMs().get(taskToExec.getVmID()).getStatus().getState().equals("down")) {
            log.info("vm: " + vm.getName() + " is exporting, waiting for next query...");
            Thread.sleep(5000);
        }
    }

    private StorageDomain getIsoDomainToExport(Api api) throws ClientProtocolException, ServerException, IOException {
        for (StorageDomain sd : api.getStorageDomains().list()) {
            if (sd.getType().equals("export")) {
                return sd;
            }
        }
        return null;
    }

}
