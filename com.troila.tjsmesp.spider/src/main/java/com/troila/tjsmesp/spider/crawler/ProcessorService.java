package com.troila.tjsmesp.spider.crawler;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.troila.tjsmesp.spider.constant.SpiderModuleEnum;
import com.troila.tjsmesp.spider.model.primary.PolicySpider;
import com.troila.tjsmesp.spider.repository.mysql.PolicySpiderRepositoryMysql;

@Component("processorService")
public class ProcessorService {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	
	 @Autowired 
	private PolicySpiderRepositoryMysql policySpiderRepositoryMysql;
 
	/**
	 * 获取之前已经下载过的文章链接，不再重复下载(以数据库中当前的存储为准)
	 * @return
	 */
	public List<String> getCrawledUrls(SpiderModuleEnum spiderMoudleEnum){
		try {			
//			//获取redis中爬取记录的总数
//			long size = redisTemplate.opsForList().size(spiderMoudleEnum.getKey());
//			if(size == 0) {
//				logger.info("当前Reids中未查询到任何之前爬取的数据，可能是初次启动爬取任务……");
//				return null;
//			}
			//从redis中获取本次爬取的所有记录
//			List<Object> redisListObj = redisTemplate.opsForList().range(spiderMoudleEnum.getKey(), 0L, size-1);
//			//进行类型转换
//			List<String> redisListUrls = redisListObj.stream().map(e->{return ((PolicySpider)e).getPublishUrl();}).collect(Collectors.toList());
//			return redisListUrls;
			List<PolicySpider> list = policySpiderRepositoryMysql.findBySpiderModule(spiderMoudleEnum.getIndex());
			List<String> listUrls = list.stream().map(e->{return ((PolicySpider)e).getPublishUrl();}).collect(Collectors.toList());
			return listUrls;
		} catch (Exception e) {
			logger.error("从本地数据库中获取已经下载过的所有链接出错，出错信息：",e);
			return null;
		}
	}
}
