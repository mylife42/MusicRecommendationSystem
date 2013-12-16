import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import models.Constants;
import models.DataSet;
import models.Song;

import org.apache.log4j.Logger;

import models.DataSet;
import models.Song;
import models.Constants;
import utils.DBReader;
import utils.Utility;
import utils.data.CrossValidationFactory;
import algos.Algorithm;
import algos.ItemBasedCollaborativeFiltering;
import algos.KNN;
import algos.NaiveBayes;
import algos.TopNPopularSongs;
import algos.UserBasedCollaborativeFiltering;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;

/**
 * This is the MAIN class of the project which aims at giving music
 * recommendations to the users in the test set according to data provided in
 * the training set.
 */
public class MusicRecommender 
{
	private static DBReader mDBReader = new DBReader();
	private DataSet mFullDataset = null;	// Entire dataset read from the database
	
	private static Logger LOG = Logger.getLogger(MusicRecommender.class);
	
	private static DecimalFormat df = new DecimalFormat("#.00"); 
	
	private static Map<String, Algorithm> getOverallAlgorithmsMap(int recommendationCount)
	{
		// Algorithms
		Algorithm overallTopNSongsAlgo 	= new TopNPopularSongs(recommendationCount);
		Algorithm kNNAlgo 				= new KNN(recommendationCount) ;
		Algorithm naiveBayesAlgo 		= new NaiveBayes(recommendationCount);
		Algorithm userBasedCollabFiltering = new UserBasedCollaborativeFiltering(recommendationCount);
		Algorithm itemBasedCollabFiltering = new ItemBasedCollaborativeFiltering(recommendationCount);

		Map<String, Algorithm> algosMap = Maps.newHashMap();
		algosMap.put(Constants.TOP_N_POPULAR, 				overallTopNSongsAlgo);
		algosMap.put(Constants.USER_BASED_COLLABORATIVE_FILTERING, userBasedCollabFiltering);
		algosMap.put(Constants.ITEM_BASED_COLLABORATIVE_FILTERING, itemBasedCollabFiltering);
		//algosMap.put(Constants.K_NEAREST_NEIGHBOUR, 		kNNAlgo);
		//algosMap.put(Constants.NAIVE_BAYES, 				naiveBayesAlgo);
		
		return algosMap;
	
	}
	
	/**
	 * Main method which will execute different Recommendation Algorithms and
	 * compare their results.
	 * 
	 * Sample run :
	 * MusicRecommender msd_test 10 40 5
	 * 
	 * @param args
	 */
	public static void main(String[] args) 
	{
		// Parse the command line arguments

		if(args == null || (args.length != 4)) 
		{
			StringBuilder errorMsg = new StringBuilder();
			errorMsg.append("Please run the program with correct arguments !!").append("\n");
			errorMsg.append("Usage : MusicRecommender <table name> <num songs to recommend> <num cross-validation folds> <num runs>");
			throw new IllegalArgumentException("Please run the program with correct arguments !!");
		}

		String dbTableName = args[0];
		int numSongRecommendationPerUser = Integer.parseInt(args[1]);
		int numCrossValidationFolds = Integer.parseInt(args[2]);
		int runs = Integer.parseInt(args[3]);
		LOG.info("Dataset Table : " + dbTableName + ", Song recommendations per user : " + 
				numSongRecommendationPerUser + ", Cross validation folds : " + numCrossValidationFolds + 
				", Job runs : " + runs);
		
		DataSet mFullDataset = mDBReader.createDataSet(dbTableName);
		
		// Run algorithms multiple times to get average accuracy results for different datasets
		// using cross-validation approach.
		boolean randomizeFolds = (runs == numCrossValidationFolds) ? false : true;
		CrossValidationFactory datasetFactory = 
				new CrossValidationFactory(mFullDataset, numCrossValidationFolds, randomizeFolds);

		Map<String, Algorithm> overallAlgosMap = getOverallAlgorithmsMap(numSongRecommendationPerUser);
		Map<String, Double> algosAccuracy = Maps.newHashMap();
		Map<String, Long> algosRunTimes = Maps.newHashMap();
		
		for(int runId = 0; runId < runs; runId++)
		 {
			Map<String, DataSet> foldDatasets = datasetFactory.getDatasets(runId);
			DataSet trainDataset = foldDatasets.get(Constants.TRAIN_DATASET);
			DataSet testVisibleDataset = foldDatasets.get(Constants.TEST_VISIBLE_DATASET);
			DataSet testHiddenDataset = foldDatasets.get(Constants.TEST_HIDDEN_DATASET);
			
			LOG.info("\n\n");
			LOG.info("Train dataset summary for run " + runId + " is " + trainDataset.getDatasetStats());
			LOG.info("Test visible dataset summary for run " + runId + " is " + testVisibleDataset.getDatasetStats());
			LOG.info("Test hidden dataset summary for run " + runId + " is " + testHiddenDataset.getDatasetStats());
			

			/**
			 * For each recommendation algorithm do the following :
			 * 
			 * 1) Build a learning model based on the algorithm.
			 * 2) Recommend top N songs based on the learned model.
			 * 3) Compare the predicted songs with the actual songs listened by a test data set user.
			 */			
			for(Map.Entry<String, Algorithm> perAlgorithmEntry : overallAlgosMap.entrySet()) 
			{
				// Getting the algorithm
				String algoName = perAlgorithmEntry.getKey();
				Algorithm algo = perAlgorithmEntry.getValue();
				LOG.info("Running '" + algoName + "' recommendation algorithm for run " + runId);
				
				// Main Step - Generating Model + Recommending + Testing Recommendation
				Stopwatch algoTimer = Stopwatch.createStarted();
				double currentAlgoAccuracy = runAlgorithm(algo, trainDataset, testVisibleDataset, testHiddenDataset);
				algoTimer.stop();
				LOG.info("Accuracy of algo '" + algoName + "' for run " + runId + " is " + 
						df.format(currentAlgoAccuracy) + " % ");
				
				// Logging algorithm's runtime
				long algoRuntime = 0;
				if(algosRunTimes.containsKey(algoName)) {
					algoRuntime = algosRunTimes.get(algoName); 
				}
				algosRunTimes.put(algoName, algoRuntime + algoTimer.elapsed(TimeUnit.SECONDS));
				
				// Summing up Algo Accuracy
				Double cumulativeAlgoAccuracy = 0.0;
				if(algosAccuracy.containsKey(algoName)) 
					cumulativeAlgoAccuracy = algosAccuracy.get(algoName);
				algosAccuracy.put(algoName, cumulativeAlgoAccuracy + currentAlgoAccuracy);
			}
		}
		
		// Display the aggregated results for all algorithms
		LOG.info("\n\n");
		LOG.info("----------------------------------------------");
		LOG.info("Overall Avg. Accuracy (NumOfRecommendations=" + numSongRecommendationPerUser + 
				", NumOfCVFolds=" + numCrossValidationFolds + ")");
		LOG.info("=====================\n");
		for(Map.Entry<String, Double> perAlgoEntry : algosAccuracy.entrySet())
		{
			String algoName = perAlgoEntry.getKey();
			Double sumAccuracies = algosAccuracy.get(algoName);
			
			double avgAccuarcy = sumAccuracies/runs;//algoRunsResult.size();
			LOG.info("'" + algoName + "' : Accuracy = " + df.format(avgAccuarcy) + " % , Time : " + 
					algosRunTimes.get(algoName) + " seconds.");
		}
		LOG.info("----------------------------------------------\n");

	}
	
	/**
	 * Method to run any algorithm with a given trainDataset and testVisibleDataset. testHiddenDataset is 
	 * used to test the accuracy of the recommendations made by the generated model of that algorithm.
	 * 
	 * @param algo					Learner Method
	 * @param trainDataset			TrainDataset
	 * @param testVisibleDataset	Test Visible Dataset (part of training dataset)
	 * @param testHiddenDataset		Actual Test Dataset
	 * @return						Accuracy of the generated model
	 */
	public static double runAlgorithm(Algorithm algo, DataSet trainDataset, 
									  DataSet testVisibleDataset, DataSet testHiddenDataset)
	{
		// Generate Model
		algo.generateModel(trainDataset);
		
		// Get Recommendations using generated model
		Map<String, List<Song>> recommendations = algo.recommend(testVisibleDataset);
		
		// Test Accuracy of generated model
		return Utility.getAccuracy(recommendations, testHiddenDataset);
	}
}
