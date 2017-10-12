package com.github.izerui.file.service.impl;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.izerui.file.entity.DeployEntity;
import com.github.izerui.file.repository.DeployRepository;
import com.github.izerui.file.service.FileService;
import com.github.izerui.file.utils.ExtendFilenameUtils;
import com.github.izerui.file.utils.RelativeDateFormat;
import com.github.izerui.file.vo.FileItem;
import com.github.izerui.file.vo.Server;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.flex.remoting.RemotingDestination;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@RemotingDestination
@Service("fileService")
@ConfigurationProperties
@Transactional
public class FileServiceImpl implements FileService {

    private static Logger log = Logger.getLogger(FileServiceImpl.class);


    public static String rootPath;

    static {
        rootPath = System.getProperty("user.dir") + File.separator;
    }


    @Autowired
    private DeployRepository deployRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public List<FileItem> listFiles() throws Exception {
        List<FileItem> files = new ArrayList<FileItem>();
        File folderFile = new File(rootPath);
        File[] filesArray = folderFile.listFiles();
        List<Server> servers = getServerList();
        for (File file : filesArray) {
            if (!file.isDirectory()) {
                FileItem fi = new FileItem();
                fi.setLashmodifyDate(new Date(file.lastModified()));
                fi.setRelativeLashmodifyDate(RelativeDateFormat.format(fi.getLashmodifyDate()));
                fi.setIsfolder(false);
                fi.setFolderpath(ExtendFilenameUtils.getFullPath(file.getPath()));
                fi.setBasename(ExtendFilenameUtils.getBaseName(file.getName()));
                fi.setExtension(ExtendFilenameUtils.getExtension(file.getName()));
                fi.setIshidden(file.isHidden());
                fi.setFilename(file.getName());
                fi.setSize(file.length());

                DeployEntity one = deployRepository.findOne(fi.getFilename());
                if (one != null) {
                    fi.setDeployTime(one.getDeployTime());
                    fi.setRelativeDeployTime(RelativeDateFormat.format(one.getDeployTime()));
                }

                File deployFile = new File(rootPath + "deploy.json");
                if (deployFile.exists()) {
                    for (Server server : servers) {
                        for (Server.Service service : server.getServices()) {
                            if (service != null && service.getFile().equals(file.getName())) {
                                if (StringUtils.isEmpty(fi.getServers())) {
                                    fi.setServers(server.getServer());
                                } else {
                                    fi.setServers(fi.getServers() + "," + server.getServer());
                                }

                            }
                        }
                    }
                }
                files.add(fi);
            }
        }


        files.sort((o1, o2) -> {
            if (o1.isIsfolder() && o2.getIsfolder()) {
                return o1.getLashmodifyDate().after(o2.getLashmodifyDate()) ? -1 : 1;
            } else if (o1.isIsfolder()) {
                return -1;
            } else if (o2.isIsfolder()) {
                return 1;
            } else {
                return o1.getLashmodifyDate().after(o2.getLashmodifyDate()) ? -1 : 1;
            }
        });

        return files;
    }


    @Override
    public String exec(String fileName) throws Exception {
        String output = "";
        boolean deployed = false;
        List<Server> servers = getServerList();
        for (Server server : servers) {
            for (Server.Service service : server.getServices()) {
                if (service != null && service.getFile().equals(fileName)) {

                    String chmodCommand = "chmod 777 /etc/ansible/application-operation.sh";
                    Process chmodProcess = Runtime.getRuntime().exec(chmodCommand);
                    chmodProcess.waitFor();


                    String command = "/bin/sh /etc/ansible/application-operation.sh " + service.getType() + " " + server.getServer() + " " + service.getFile();
                    Process execProcess = Runtime.getRuntime().exec(command);
                    execProcess.waitFor();

                    output += IOUtils.toString(execProcess.getInputStream(), "UTF-8") + "\n";
                    log.info(output);

                    deployed = true;
                }
            }
        }

        if (!deployed) {
            throw new RuntimeException("不支持该文件");
        }

        //保存发布记录
        DeployEntity one = deployRepository.findOne(fileName);
        if (one == null) {
            one = new DeployEntity();
            one.setFileName(fileName);
        }
        one.setDeployTime(new Date());
        deployRepository.save(one);

        return output;
    }


    public List<Server> getServerList() throws Exception {
        File file = new File(rootPath + "deploy.json");
        if (!file.exists()) {
            throw new RuntimeException("部署配置文件不存在");
        }
        JavaType javaType = objectMapper.getTypeFactory().constructParametricType(List.class, Server.class);
        List<Server> servers = objectMapper.readValue(new File(rootPath + "deploy.json"), javaType);
        return servers;
    }
}
