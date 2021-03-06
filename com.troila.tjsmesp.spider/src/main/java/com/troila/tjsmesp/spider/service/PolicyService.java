package com.troila.tjsmesp.spider.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.troila.tjsmesp.spider.constant.PolicyStatus;
import com.troila.tjsmesp.spider.constant.SpiderModuleEnum;
import com.troila.tjsmesp.spider.model.primary.PolicySpider;
import com.troila.tjsmesp.spider.model.secondary.SmePolicy;
import com.troila.tjsmesp.spider.repository.informix.SmePolicyRespositoryInformix;
import com.troila.tjsmesp.spider.repository.mysql.PolicySpiderRepositoryMysql;
import com.troila.tjsmesp.spider.util.MD5Util;
import com.troila.tjsmesp.spider.util.ReduceHtml2Text;
import com.troila.tjsmesp.spider.util.TimeUtils;
@Service
public class PolicyService {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	
	//当同步到informix数据库时，默认的政策发布者
	@Value("${data.sync.default.publisher}")
	private String defaultPublisher;
	
	//初次同步数据时，同步数据的最大条目数
	@Value("${data.sync.first.max.number}")
	private int syncFirstMaxNumber;
	
	@Autowired
	private SmePolicyRespositoryInformix smePolicyRespositoryInformix;
	
	@Autowired
	private PolicySpiderRepositoryMysql policySpiderRepositoryMysql;
	
	@Autowired
	private RedisTemplate<String, Object> redisTemplate;
	
	private Map<String,PolicySpider> map = new ConcurrentHashMap<>();
	
	/**
	 * mysql数据库的数据实体类与informix数据库实体类之间的转换
	 * @param policySpider
	 * @return
	 */
	public SmePolicy convertTo(PolicySpider policySpider) {
		if(policySpider == null) {
			logger.error("类型转换参数错误：参数policySpider为null");
			return null;			
		}
		SmePolicy smePolicy = new SmePolicy();
		//待确定字段内容
		if(policySpider.getSpiderModule()==SpiderModuleEnum.POLICY_NEWEST.getIndex()) {
			//如果是政策原文,父类Id默认-1
			smePolicy.setParentId(-1);	
			smePolicy.setPolicyLevel(policySpider.getPolicyLevel());   //有待区分对待
			smePolicy.setSource(policySpider.getPublishUnit());
			smePolicy.setReferenceNumber(policySpider.getPublishNo());
		}else {
			//如果是政策解读，需要找到该解读的文章的父类id
//			int parentId = getParentIdForReadingActicle(policySpider.getPublishUrl());
//			if(parentId == -1) {
//				logger.error("类型转换时出现错误，政策解读记录{}:{}找不到对应的原文记录",policySpider.getTitle(),policySpider.getPublishUrl());
//				return null;			
//			}
			smePolicy.setParentId(-1);
			PolicySpider parentPolicy = getParentPolicyForReadingActicleMysql(policySpider.getPublishUrl());
			if(parentPolicy == null) {
				logger.error("类型转换时出现错误，政策解读记录{}:{}找不到对应的原文记录",policySpider.getTitle(),policySpider.getPublishUrl());
				return null;
			}
			smePolicy.setPolicyLevel(parentPolicy.getPolicyLevel());   //有待区分对待
			smePolicy.setSource(policySpider.getPublishUnit().contains("国家政策解读")?parentPolicy.getPublishUnit():policySpider.getPublishUnit());
			smePolicy.setReferenceNumber(parentPolicy.getPublishNo());
		}
		smePolicy.setTitle(policySpider.getTitle());
		smePolicy.setType(policySpider.getSpiderModule());
		smePolicy.setStripedContent(ReduceHtml2Text.removeHtmlTag(policySpider.getContent()));
//		smePolicy.setPolicyAbstract("同步过来的数据，这是摘要内容");
//		String content_temp = ReduceHtml2Text.removeHtmlTag(policySpider.getContent());
		String content = "<div class='articleList'><h3 class='articleTit'>摘要</h3><div class='articleTxt'>"+""
					+    "</div><h3 class='articleTit'>正文</h3><div class='articleTxt'>"+policySpider.getContent()
					+ 	 "</div><h3 class='articleTit'>联系人及联系方式</h3><div class='articleTxt'></div></div>";
		smePolicy.setContent(content);
		smePolicy.setPublishDate(policySpider.getPublishDate().getTime());
		
		smePolicy.setStatus(PolicyStatus.Pending);
		
		smePolicy.setUrl(policySpider.getPublishUrl());
		
		//有待进一步确定
		smePolicy.setGmtStart(policySpider.getPublishDate());
		smePolicy.setGmtEnd(new Date());   
		smePolicy.setGmtCreate(new Date());
		smePolicy.setGmtModify(new Date());
		smePolicy.setFromSite(policySpider.getFromSite());
//		smePolicy.setFromLink(policySpider.getFromLink());
		//修改内容，转载链接改为原文链接了
		smePolicy.setFromLink(policySpider.getPublishUrl());
		smePolicy.setGmtForward(new Date());
		
		
		
		smePolicy.setIndustry("ALL");
		smePolicy.setArea("全国");
		smePolicy.setPriority(0);
		smePolicy.setCategory("综合政策");
		smePolicy.setPublishType("platform");
		smePolicy.setPublisher(defaultPublisher);
		//如果有附件下载链接，设置附件
		smePolicy.setAttachments(policySpider.getAttachment());
		return smePolicy;
	}
	
	/**
	 * 最新政策进行数据同步
	 * @return
	 */
	public List<SmePolicy> dataSync(SpiderModuleEnum spiderMoudleEnum){
		try {	
			logger.info("{}开始执行数据更新以及同步过程，更新模块为{},请稍候……",TimeUtils.getLongFormatDate(new Date()),spiderMoudleEnum.getName());
			//先获取mysql数据库中的所有该类型的数据
			List<PolicySpider> mysqlList = policySpiderRepositoryMysql.findBySpiderModule(spiderMoudleEnum.getIndex());
			//获取redis中爬取记录的总数
			long size = redisTemplate.opsForList().size(spiderMoudleEnum.getKey());
			if(size == 0) {
				logger.info("当前Reids中未查询到任何爬取的数据，可能上次爬取过程中出错了……");
				return null;
			}
			//从redis中获取本次爬取的所有记录
			List<Object> redisListObj = redisTemplate.opsForList().range(spiderMoudleEnum.getKey(), 0L, size-1);
			//进行类型转换
			List<PolicySpider> redisList = redisListObj.stream().map(e->{return (PolicySpider)e;}).collect(Collectors.toList());
			map = mysqlList.stream().collect(Collectors.toMap(PolicySpider::getId, e->e));		
				
			List<PolicySpider> updateList = new ArrayList<PolicySpider>();
			//redis中本次爬取的所有记录中筛选出原来数据库中没有的记录，即为距离上次爬取的这段时间的更新的记录
			updateList = redisList.stream()
								.filter(p->!map.containsKey(MD5Util.getMD5(p.getPublishUrl())))
								.collect(Collectors.toList());
			if(spiderMoudleEnum == SpiderModuleEnum.POLICY_READING) {  //如果是政策解读，需要对发文部门，发文字号，政策级别进行设置
				updateList = updateList.stream()
										.map(e->{
											PolicySpider parentPolicy = getParentPolicyForReadingActicleMysql(e.getPublishUrl());
											if(parentPolicy != null) {
												e.setParentId(parentPolicy.getId());
												e.setPublishNo(parentPolicy.getPublishNo());
												e.setPolicyLevel(parentPolicy.getPolicyLevel());
												if(e.getPublishUnit().equals("国家政策解读")) {
													e.setPublishUnit(parentPolicy.getPublishUnit());
												}
											}
											return e;})
										.collect(Collectors.toList());
			}
			//将本次新增的内容保存进本地数据库
			if(updateList.size()>0) {
				policySpiderRepositoryMysql.saveAll(updateList);	
				logger.info("Mysql本地库本次更新模块：{}完成,增加条目数为：{} 条",spiderMoudleEnum.getName(),updateList.size());
			}
			//将本次新增的内容同步到中小企数据库中
			List<SmePolicy> resultList = updateList.stream()
					.filter(p->convertTo(p)!=null)       //过滤掉转换完后为null的记录
					.map(e->{return convertTo(e);})    
					.collect(Collectors.toList());		
//			List<SmePolicy> resultListSave = smePolicyRespositoryInformix.saveAll(resultList);
//			logger.info("Informix库本次同步数据完成，本次同步模块：{},增加条目数为：{} 条",spiderMoudleEnum.getName(),resultListSave.size());
//			logger.info("Informix库本次同步数据完成，本次同步模块：{},增加条目数为：{} 条",spiderMoudleEnum.getName(),resultList.size());
			return resultList;	
//			return updateList;
		}catch (Exception e) {
			logger.error("本次更新模块：{} 出现异常,信息如下：",spiderMoudleEnum.getName(),e);
			return null;
		}finally {
			map.clear();
		}
	}
	
	/**
	 * 同步最近一周的数据
	 * @param spiderMoudleEnum
	 * @return
	 */
	public List<SmePolicy> dataSync_test(SpiderModuleEnum spiderMoudleEnum){
//		List<PolicySpider> mysqlList = policySpiderRepositoryMysql.findBySpiderModule(spiderMoudleEnum.getIndex());
//		List<SmePolicy> resultList = mysqlList.stream().map(e->{return convertTo(e);}).collect(Collectors.toList());
//		return smePolicyRespositoryInformix.saveAll(resultList);
		List<PolicySpider> mysqlList = policySpiderRepositoryMysql.findByPublishDateGreaterThanEqualAndSpiderModule(TimeUtils.getLastWeek(), 0);
		List<SmePolicy> resultList = mysqlList.stream().map(e->{return convertTo(e);}).collect(Collectors.toList());
		return smePolicyRespositoryInformix.saveAll(resultList);	
	}
	
	/**
	 * 为爬取的解读文章找到政策原文文章
	 * @param publicshUrl
	 * @return
	 */
	public int getParentIdForReadingActicle(String publishUrl) {
		List<PolicySpider>  list = policySpiderRepositoryMysql.findByArticleReadingContaining(publishUrl);
//		PolicySpider parentPolicy = policySpiderRepositoryMysql.findByPublishUrl(publishUrl);
		if(list == null || list.size()==0) {
			logger.error("查找文章链接为：{} 的父类文章出错,Mysql库中查询结果为空",publishUrl);
			return -1;			
		}
		PolicySpider parentPolicy = list.get(0);
		SmePolicy parentPolicyInformix = smePolicyRespositoryInformix.findByFromLink(parentPolicy.getPublishUrl());
		if(parentPolicyInformix == null) {
			logger.error("查找文章链接为：{} 的父类文章id出错,Informix库中查询结果为空",parentPolicy.getPublishUrl());
			return -1;
		}
		return parentPolicyInformix.getId();
	}
	
	
	/**
	 * 为爬取的解读文章找到政策原文文章
	 * @param publicshUrl
	 * @return
	 */
	//http://zcydt.fzgg.tj.gov.cn/zcbjd/sjbmjd/sjrj_237/201703/t20170321_19918.shtml
	public PolicySpider getParentPolicyForReadingActicleMysql(String publishUrl) {
		List<PolicySpider>  list = policySpiderRepositoryMysql.findByArticleReadingContaining(publishUrl);
		if(list == null || list.size() <= 0) {
			logger.error("查找文章链接为：{} 的父类文章,Mysql库中查询结果为空",publishUrl);	
			return null;
		}
		if(list.size() > 1) {
			logger.info("查找文章链接为：{} 的父类文章完成，父类文章个数为{},信息分别为",publishUrl,list.size());	
		}
		PolicySpider parentPolicy = list.get(0);
		logger.info("查找文章链接为：{} 的父类文章完成，父类文章个数为{},文章链接为：{}",publishUrl,list.size(),parentPolicy.getPublishUrl());	
		return parentPolicy;
	}
	
	public PolicySpider getParentIdForReadingActicle2(String publishUrl) {
		List<PolicySpider>  list = policySpiderRepositoryMysql.findByArticleReadingContaining(publishUrl);
//		PolicySpider parentPolicy = policySpiderRepositoryMysql.findByPublishUrl(publishUrl);		
		return list.get(0);
	}
}
