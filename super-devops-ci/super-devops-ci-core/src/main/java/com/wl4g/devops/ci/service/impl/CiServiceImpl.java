package com.wl4g.devops.ci.service.impl;

import com.wl4g.devops.ci.devtool.DevConfig;
import com.wl4g.devops.ci.service.CiService;
import com.wl4g.devops.ci.service.TaskService;
import com.wl4g.devops.ci.subject.BaseSubject;
import com.wl4g.devops.ci.subject.JarSubject;
import com.wl4g.devops.ci.subject.TarSubject;
import com.wl4g.devops.common.bean.ci.*;
import com.wl4g.devops.common.bean.scm.AppGroup;
import com.wl4g.devops.common.bean.scm.AppInstance;
import com.wl4g.devops.common.bean.scm.Environment;
import com.wl4g.devops.common.constants.CiDevOpsConstants;
import com.wl4g.devops.dao.ci.ProjectDao;
import com.wl4g.devops.dao.ci.TriggerDao;
import com.wl4g.devops.dao.ci.TriggerDetailDao;
import com.wl4g.devops.dao.scm.AppGroupDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author vjay
 * @date 2019-05-16 14:50:00
 */
@Service
public class CiServiceImpl implements CiService {

	@Autowired
	private DevConfig devConfig;

	@Autowired
	private AppGroupDao appGroupDao;

	@Autowired
	private TriggerDao triggerDao;

	@Autowired
	private TriggerDetailDao triggerDetailDao;

	@Autowired
	private ProjectDao projectDao;

	@Autowired
	private TaskService taskService;



	@Override public List<AppGroup> grouplist() {
		return appGroupDao.grouplist();
	}

	@Override public List<Environment> environmentlist(String groupId) {
		return appGroupDao.environmentlist(groupId);
	}

	@Override public List<AppInstance> instancelist(AppInstance appInstance) {
		return appGroupDao.instancelist(appInstance);
	}

	@Override
	public Trigger getTriggerByProjectAndBranch(Integer projectId, String branchName) {
		Map<String,Object> map = new HashMap<>();
		map.put("projectId",projectId);
		map.put("branchName",branchName);
		Trigger trigger = triggerDao.getTriggerByProjectAndBranch(map);
		if(null==trigger){
			return null;
		}
		List<TriggerDetail> triggerDetails = triggerDetailDao.getDetailByTriggerId(trigger.getId());
		if(null==triggerDetails){
			return null;
		}
		trigger.setTriggerDetails(triggerDetails);
		return trigger;
	}


	public void hook(String projectName,String branchName,String url){
		Project project = projectDao.getByProjectName(projectName);
		Assert.notNull(project,"project not found, please config first");
		//AppGroup appGroup = appGroupDao.getAppGroup(project.getAppGroupId().toString());
		//String alias = appGroup.getName();
		Trigger trigger = getTriggerByProjectAndBranch(project.getId(),branchName);
		Assert.notNull(trigger,"trigger not found, please config first");

		List<AppInstance> instances = new ArrayList<>();
		for(TriggerDetail triggerDetail : trigger.getTriggerDetails()){
			AppInstance instance = appGroupDao.getAppInstance(triggerDetail.getInstanceId().toString());
			instances.add(instance);
		}
		Assert.notEmpty(instances,"instances not found, please config first");

		//TODO get sha
		String sha = null;

		Task task = taskService.createTask(project,instances,CiDevOpsConstants.TASK_TYPE_TRIGGER,CiDevOpsConstants.TASK_STATUS_CREATE,branchName,sha,null,null,trigger.getTarType());
		BaseSubject subject = getSubject(task);

		try {
			////update task--running
			taskService.updateTaskStatus(task.getId(),CiDevOpsConstants.TASK_STATUS_RUNNING);
			//TODO exec
			subject.exec();
			//update task--success
			taskService.updateTaskStatus(task.getId(),CiDevOpsConstants.TASK_STATUS_SUCCESS);
		} catch (Exception e) {
			//update task--fail
			taskService.updateTaskStatus(task.getId(),CiDevOpsConstants.TASK_STATUS_FAIL);
			e.printStackTrace();
		}

	}

	private BaseSubject getSubject(int tarType,String path, String url, String branch, String alias,String tarPath,List<AppInstance> instances,List<TaskDetail> taskDetails){
		switch(tarType){
			case CiDevOpsConstants.TAR_TYPE_TAR :
				return new TarSubject(path, url, branch, alias,tarPath,instances,taskDetails);
			case CiDevOpsConstants.TAR_TYPE_JAR :
				return new JarSubject(path, url, branch, alias,tarPath,instances,taskDetails);
			case CiDevOpsConstants.TAR_TYPE_OTHER :
				//return new OtherSubject();
			default :
				throw new RuntimeException("unsuppost type:"+tarType);
		}
	}


	public BaseSubject getSubject(Task task){
		Assert.notNull(task,"task can not be null");
		Project project = projectDao.selectByPrimaryKey(task.getProjectId());
		Assert.notNull(project,"project can not be null");
		AppGroup appGroup = appGroupDao.getAppGroup(project.getAppGroupId().toString());
		Assert.notNull(appGroup,"appGroup can not be null");

		List<TaskDetail> taskDetails = taskService.getDetailByTaskId(task.getId());
		Assert.notNull(taskDetails,"taskDetails can not be null");
		List<AppInstance> instances = new ArrayList<>();
		for(TaskDetail taskDetail : taskDetails){
			AppInstance instance = appGroupDao.getAppInstance(taskDetail.getInstanceId().toString());
			instances.add(instance);
		}
		return getSubject(task.getTarType(),devConfig.getGitBasePath()+"/"+project.getProjectName(),project.getGitUrl(),task.getBranchName(),appGroup.getName(),project.getTarPath(),instances,taskDetails);
	}



}