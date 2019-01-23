package com.troila.tjsmesp.spider.repository.mysql;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.troila.tjsmesp.spider.model.PolicySpider;

public interface PolicySpiderRepositoryMysql extends JpaRepository<PolicySpider, Integer>{
	
	public PolicySpider findById(String id);
		
	public List<PolicySpider> findBySpiderModule(int spiderModule);
	
	public List<PolicySpider> findByStripContentContains(String str);
	
	public PolicySpider findByPublishUrl(String publishUrl);
	
	public PolicySpider findByArticleReadingContaining(String articleReading);
	
	public List<PolicySpider> findByPublishUnitContaining(String publishUntLike);
}