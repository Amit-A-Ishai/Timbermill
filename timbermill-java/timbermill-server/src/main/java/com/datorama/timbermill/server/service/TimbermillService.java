package com.datorama.timbermill.server.service;

import com.datorama.oss.timbermill.ElasticsearchClient;
import com.datorama.oss.timbermill.TaskIndexer;
import com.datorama.oss.timbermill.common.ElasticsearchUtil;
import com.datorama.oss.timbermill.common.KamonConstants;
import com.datorama.oss.timbermill.common.disk.DiskHandler;
import com.datorama.oss.timbermill.common.disk.DiskHandlerUtil;
import com.datorama.oss.timbermill.cron.CronsRunner;
import com.datorama.oss.timbermill.unit.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class TimbermillService {

	private static final Logger LOG = LoggerFactory.getLogger(TimbermillService.class);

	private TaskIndexer taskIndexer;
	private BlockingQueue<Event> eventsQueue;
	private BlockingQueue<Event> overflowedQueue;

	private boolean keepRunning = true;
	private boolean stoppedRunning = false;
	private long terminationTimeout;
	private DiskHandler diskHandler;
	private CronsRunner cronsRunner;

	@Autowired
	public TimbermillService(@Value("${INDEX_BULK_SIZE:200000}") Integer indexBulkSize,
							 @Value("${ELASTICSEARCH_URL:http://localhost:9200}") String elasticUrl,
							 @Value("${ELASTICSEARCH_AWS_REGION:}") String awsRegion,
							 @Value("${ELASTICSEARCH_USER:}") String elasticUser,
							 @Value("${ELASTICSEARCH_PASSWORD:}") String elasticPassword,
							 @Value("${ELASTICSEARCH_NUMBER_OF_SHARDS:10}") int numberOfShards,
							 @Value("${ELASTICSEARCH_NUMBER_OF_REPLICAS:1}") int numberOfReplicas,
							 @Value("${ELASTICSEARCH_INDEX_MAX_AGE:7}") int maxIndexAge,
							 @Value("${ELASTICSEARCH_INDEX_MAX_GB_SIZE:100}") int maxIndexSizeInGB,
							 @Value("${ELASTICSEARCH_INDEX_MAX_DOCS:1000000000}") int maxIndexDocs,
							 @Value("${ELASTICSEARCH_MAX_TOTAL_FIELDS:4000}") int maxTotalFields,
							 @Value("${ELASTICSEARCH_MAX_SEARCH_SIZE:1000}") int searchMaxSize,
							 @Value("${ELASTICSEARCH_ACTION_TRIES:3}") int numOfElasticSearchActionsTries,
							 @Value("${INDEXING_THREADS:10}") int indexingThreads,
							 @Value("${DAYS_ROTATION:90}") Integer daysRotation,
							 @Value("${TIMBERMILL_VERSION:}") String timbermillVersion,
							 @Value("${TERMINATION_TIMEOUT_SECONDS:60}") int terminationTimeoutSeconds,
							 @Value("${PLUGINS_JSON:[]}") String pluginsJson,
							 @Value("${EVENT_QUEUE_CAPACITY:10000000}") int eventsQueueCapacity,
							 @Value("${OVERFLOWED_QUEUE_CAPACITY:10000000}") int overFlowedQueueCapacity,
							 @Value("${MAX_BULK_INDEX_FETCHES:3}") int maxBulkIndexFetches,
							 @Value("${MERGING_CRON_EXPRESSION:0 0/10 * 1/1 * ? *}") String mergingCronExp,
							 @Value("${DELETION_CRON_EXPRESSION:0 0 12 1/1 * ? *}") String deletionCronExp,
							 @Value("${DELETION_CRON_MAX_INDICES_IN_PARALLEL:1}") int expiredMaxIndicesToDeleteInParallel,
							 @Value("${DISK_HANDLER_STRATEGY:sqlite}") String diskHandlerStrategy,
							 @Value("${BULK_PERSISTENT_FETCH_CRON_EXPRESSION:0 0/10 * 1/1 * ? *}") String bulkPersistentFetchCronExp,
							 @Value("${EVENTS_PERSISTENT_FETCH_CRON_EXPRESSION:0 0/5 * 1/1 * ? *}") String eventsPersistentFetchCronExp,
							 @Value("${MAX_FETCHED_BULKS_IN_ONE_TIME:10}") int maxFetchedBulksInOneTime,
							 @Value("${MAX_INSERT_TRIES:10}") int maxInsertTries,
							 @Value("${LOCATION_IN_DISK:/db}") String locationInDisk,
							 @Value("${SCROLL_LIMITATION:1000}") int scrollLimitation,
							 @Value("${SCROLL_TIMEOUT_SECONDS:60}") int scrollTimeoutSeconds,
							 @Value("${MAXIMUM_TASKS_CACHE_WEIGHT:100000000}") long maximumTasksCacheWeight,
							 @Value("${MAXIMUM_ORPHANS_CACHE_WEIGHT:1000000000}") long maximumOrphansCacheWeight,
							 @Value("${CACHE_STRATEGY:}") String cacheStrategy,
							 @Value("${REDIS_MAX_MEMORY:}") String redisMaxMemory,
							 @Value("${REDIS_MAX_MEMORY_POLICY:}") String redisMaxMemoryPolicy,
							 @Value("${REDIS_HOST:localhost}") String redisHost,
							 @Value("${REDIS_PORT:6379}") int redisPort,
							 @Value("${REDIS_PASS:}") String redisPass,
							 @Value("${REDIS_USE_SSL:false}") Boolean redisUseSsl,
							 @Value("${REDIS_TTL_IN_SECONDS:604800}") int redisTtlInSeconds,
							 @Value("${REDIS_GET_SIZE:604800}") int redisGetSize,
							 @Value("${REDIS_POOL_MIN_IDLE:5}") int redisPoolMinIdle,
							 @Value("${REDIS_POOL_MAX_TOTAL:10}") int redisPoolMaxTotal,
							 @Value("${FETCH_BY_IDS_PARTITIONS:10000}") int fetchByIdsPartitions){

		eventsQueue = new LinkedBlockingQueue<>(eventsQueueCapacity);
		overflowedQueue = new LinkedBlockingQueue<>(overFlowedQueueCapacity);
		terminationTimeout = terminationTimeoutSeconds * 1000;

		Map<String, Object> params = DiskHandler.buildDiskHandlerParams(maxFetchedBulksInOneTime, maxInsertTries, locationInDisk);
		diskHandler = DiskHandlerUtil.getDiskHandler(diskHandlerStrategy, params);
		ElasticsearchClient es = new ElasticsearchClient(elasticUrl, indexBulkSize, indexingThreads, awsRegion, elasticUser,
				elasticPassword, maxIndexAge, maxIndexSizeInGB, maxIndexDocs, numOfElasticSearchActionsTries, maxBulkIndexFetches, searchMaxSize, diskHandler, numberOfShards, numberOfReplicas,
				maxTotalFields, null, scrollLimitation, scrollTimeoutSeconds, fetchByIdsPartitions, expiredMaxIndicesToDeleteInParallel);

		taskIndexer = new TaskIndexer(pluginsJson, daysRotation, es, timbermillVersion, maximumOrphansCacheWeight,
				maximumTasksCacheWeight, cacheStrategy, redisHost, redisPort, redisPass, redisMaxMemory, redisMaxMemoryPolicy, redisUseSsl, redisTtlInSeconds, redisGetSize, redisPoolMinIdle, redisPoolMaxTotal);
		cronsRunner = new CronsRunner();
		cronsRunner.runCrons(bulkPersistentFetchCronExp, eventsPersistentFetchCronExp, diskHandler, es, deletionCronExp,
				eventsQueue, overflowedQueue, mergingCronExp);
		startQueueSpillerThread();
		startWorkingThread();
	}

	private void startQueueSpillerThread() {
		Thread spillerThread = new Thread(() -> {
			LOG.info("Starting Queue Spiller Thread");
			while (keepRunning) {
				diskHandler.spillOverflownEventsToDisk(overflowedQueue);
				try {
					Thread.sleep(ElasticsearchUtil.THREAD_SLEEP);
				} catch (InterruptedException e) {
					LOG.error("InterruptedException was thrown from TaskIndexer:", e);
				}
			}
		});
		spillerThread.start();
	}

	private void startWorkingThread() {
		Thread workingThread = new Thread(() -> {
			LOG.info("Timbermill has started");
			while (keepRunning) {
				ElasticsearchUtil.drainAndIndex(eventsQueue, taskIndexer);
			}
			stoppedRunning = true;
		});
		workingThread.start();
	}

	@PreDestroy
	public void tearDown(){
		LOG.info("Gracefully shutting down Timbermill Server.");
		keepRunning = false;
		long currentTimeMillis = System.currentTimeMillis();
		while(!stoppedRunning && !reachTerminationTimeout(currentTimeMillis)){
			try {
				Thread.sleep(ElasticsearchUtil.THREAD_SLEEP);
			} catch (InterruptedException ignored) {}
		}
		if (diskHandler != null){
			diskHandler.close();
		}
		taskIndexer.close();
		cronsRunner.close();
		LOG.info("Timbermill server was shut down.");
	}

	private boolean reachTerminationTimeout(long starTime) {
		boolean reachTerminationTimeout = System.currentTimeMillis() - starTime > terminationTimeout;
		if (reachTerminationTimeout){
			LOG.warn("Timbermill couldn't gracefully shutdown in {} seconds, was killed with {} events in internal buffer", terminationTimeout / 1000, eventsQueue.size());
		}
		return reachTerminationTimeout;
	}

	void handleEvents(Collection<Event> events){
		for (Event event : events) {
			if(!this.eventsQueue.offer(event)){
				if (!overflowedQueue.offer(event)){
					diskHandler.spillOverflownEventsToDisk(overflowedQueue);
					if (!overflowedQueue.offer(event)) {
						LOG.error("OverflowedQueue is full, event {} was discarded", event.getTaskId());
					}
				}
				else {
					KamonConstants.MESSAGES_IN_OVERFLOWED_QUEUE_RANGE_SAMPLER.withoutTags().increment();
				}
			}
			else{
				KamonConstants.MESSAGES_IN_INPUT_QUEUE_RANGE_SAMPLER.withoutTags().increment();
			}
		}
	}
}
