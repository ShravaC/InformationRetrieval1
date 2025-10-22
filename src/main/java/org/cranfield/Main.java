package org.cranfield;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;

import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TermQuery;

import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;

import org.apache.lucene.index.Term;

import org.apache.lucene.store.FSDirectory;

import java.io.*;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;


/**
 * CranfieldFullEval.java
 *
 * Single-file program that:
 * - Parses cran.all and indexes it with a chosen Analyzer
 * - Interactive search with field boosts and choice of Similarity (scoring)
 * - Batch mode: generate TREC-formatted results for queries
 * - Internal evaluation: compute Precision, Recall, F1, AP, MAP, and 11-point interpolated P-R
 *
 * Requirements:
 * - Java 21+
 * - Lucene 10.x dependencies (core, analyzers-common, queryparser) in pom.xml
 *
 * Usage:
 * 1. Place cran.all in project root
 * 2. Compile & run via Maven exec plugin, specify mainClass cranfield.CranfieldFullEval
 *
 * Notes:
 * - Qrels file expected in TREC qrel-like format: "<qid> 0 <docid> <relevance>"
 * - Query file expected in cran.qry form (uses .I and .W tags)
 */
public class CranFieldParserIndexer {

    private static final String ROOT_INDEX_PATH = "index/";
    private static final Path INTERACTIVE_INDEX_PATH =  Paths.get(ROOT_INDEX_PATH+"index");
    private static final String CRAN_FILE = "cran/cran.all.1400";

    private static final int DEFAULT_TOP_K = 100;

    public static class CranFieldDocument {
        public String id = "";
        public String title = "";
        public String author = "";
        public String biblio = "";
        public String body = "";
    }

    public static List<CranFieldDocument> parseCranField(File cranAll) throws IOException {
        List<CranFieldDocument> docs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(cranAll))) {
            String line;
            CranFieldDocument cur = null;
            String section = null;
            StringBuilder sb = new StringBuilder();
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(".I ")) {
                    if (cur != null) {
                        assignSection(cur, section, sb.toString().trim());
                        docs.add(cur);
                    }
                    cur = new CranFieldDocument();
                    cur.id = line.substring(3).trim();
                    section = null;
                    sb.setLength(0);
                } else if (line.equals(".T") || line.equals(".A") || line.equals(".B") || line.equals(".W")) {
                    if (cur != null && section != null) assignSection(cur, section, sb.toString().trim());
                    section = line.substring(1);
                    sb.setLength(0);
                } else if (section != null) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(line);
                }
            }
            if (cur != null) {
                assignSection(cur, section, sb.toString().trim());
                docs.add(cur);
            }
        }
        return docs;
    }

    private static void assignSection(CranFieldDocument doc, String section, String text) {
        if (section == null) return;
        switch (section) {
            case "T": doc.title = text; break;
            case "A": doc.author = text; break;
            case "B": doc.biblio = text; break;
            case "W": doc.body = text; break;
        }
    }

    public static void buildIndex(Path indexPath, List<CranFieldDocument> docs, Analyzer analyzer) throws Exception {
        var dir = FSDirectory.open(indexPath);
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        try (IndexWriter writer = new IndexWriter(dir, iwc)) {
            for (CranFieldDocument cd : docs) {
                Document d = new Document();
                d.add(new StringField("id", cd.id == null ? "" : cd.id, Field.Store.YES));
                d.add(new TextField("title", cd.title == null ? "" : cd.title, Field.Store.YES));
                d.add(new TextField("author", cd.author == null ? "" : cd.author, Field.Store.YES));
                d.add(new TextField("biblio", cd.biblio == null ? "" : cd.biblio, Field.Store.NO));
                d.add(new TextField("body", cd.body == null ? "" : cd.body, Field.Store.YES));
                writer.addDocument(d);
            }
            writer.commit();
        }
    }

    public static Analyzer chooseAnalyzer(Scanner sc) {
        System.out.println("Select Analyzer (default = EnglishAnalyzer):");
        System.out.println("1: StandardAnalyzer");
        System.out.println("2: EnglishAnalyzer");
        System.out.println("3: SimpleAnalyzer");
        System.out.println("4: WhitespaceAnalyzer");
        System.out.print("> ");
        String input = sc.nextLine().trim();
        return switch (input) {
            case "1" -> new StandardAnalyzer();
            case "3" -> new SimpleAnalyzer();
            case "4" -> new WhitespaceAnalyzer();
            default -> new EnglishAnalyzer(); // default
        };
    }

    private static MultiFieldQueryParser makeParser(Analyzer analyzer, float titleBoost, float bodyBoost) {
        String[] fields = {"title", "body"};
        Map<String, Float> boosts = Map.of("title", titleBoost, "body", bodyBoost);
        return new MultiFieldQueryParser(fields, analyzer, boosts);
    }

    public static void interactiveSearch(IndexSearcher searcher, Analyzer analyzer, Scanner sc, float titleBoost, float bodyBoost) throws IOException {
        MultiFieldQueryParser parser = makeParser(analyzer, titleBoost, bodyBoost);
        System.out.println("Enter queries. Type ':q' to return to menu.");
        while (true) {
            System.out.print("\nQuery: ");
            String line = sc.nextLine().replaceAll("[\\?\\*]", " ");;
            if (line == null || line.equals(":q")) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            // optional filters
            System.out.print("Filter by author (optional): ");
            String authorFilter = sc.nextLine().trim();
            System.out.print("Filter by title keyword (optional): ");
            String titleFilter = sc.nextLine().trim();

            Query mainQuery;
            try {
                mainQuery = parser.parse(line);
            } catch (ParseException e) {
                System.err.println("Failed to parse query: " + e.getMessage());
                continue;
            }

            BooleanQuery.Builder combined = new BooleanQuery.Builder();
            combined.add(mainQuery, BooleanClause.Occur.MUST);
            if (!authorFilter.isEmpty()) {
                combined.add(new TermQuery(new Term("author", authorFilter)), BooleanClause.Occur.FILTER);
            }
            if (!titleFilter.isEmpty()) {
                combined.add(new TermQuery(new Term("title", titleFilter)), BooleanClause.Occur.FILTER);
            }

            TopDocs topDocs = searcher.search(combined.build(), 10);
            System.out.println("Total hits (approx): " + topDocs.totalHits);
            int rank = 1;
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String snippet = doc.get("body");
                if (snippet != null && snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                System.out.printf("%2d. id=%s score=%.4f title=%s\n    %s\n",
                        rank, doc.get("id"), sd.score, doc.get("title"), snippet);
                rank++;
            }
        }
    }

    public static LinkedHashMap<String, String> loadQueries(String queriesFile) throws IOException {
        LinkedHashMap<String, String> queries = new LinkedHashMap<>();
        int id = 1; // start ID from 1

        try (BufferedReader br = new BufferedReader(new FileReader(queriesFile))) {
            String line;
            boolean inW = false;
            StringBuilder sb = new StringBuilder();

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(".I")) {
                    if (!sb.isEmpty()) {
                        queries.put(String.valueOf(id), sb.toString().trim());
                        id++; // increment ID for the next query
                    }
                    sb.setLength(0);
                    inW = false;
                } else if (line.equals(".W")) {
                    inW = true;
                } else if (inW) {
                    sb.append(line).append(' ');
                }
            }

            if (!sb.isEmpty()) {
                queries.put(String.valueOf(id), sb.toString().trim());
            }
        }
        return queries;
    }

    public static Map<String, Map<String, Integer>> loadQrels(String qrelFile) throws IOException {
        Map<String, Map<String, Integer>> qrels = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(qrelFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;
                String qid = parts[0];
                String docid = parts[2];
                int rel = Integer.parseInt(parts[3]);
                qrels.computeIfAbsent(qid, k -> new HashMap<>()).put(docid, rel);
            }
        }
        return qrels;
    }

    public static void generateTrecResults(IndexSearcher searcher, Analyzer analyzer, String queriesFile, String outputFile,
                                           int topK, float titleBoost, float bodyBoost) throws Exception {
        LinkedHashMap<String, String> queries = loadQueries(queriesFile);
        MultiFieldQueryParser parser = makeParser(analyzer, titleBoost, bodyBoost);

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputFile))) {
            for (Map.Entry<String, String> e : queries.entrySet()) {
                String qid = e.getKey();
                String text = e.getValue();
                String safeQuery = text.replaceAll("[\\?\\*]", " ");
                Query q = parser.parse(safeQuery);
                TopDocs topDocs = searcher.search(q, topK);
                int rank = 1;
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    String docId = doc.get("id");
                    pw.printf("%s Q0 %s %d %.6f cranLucene\n", qid, docId, rank, sd.score);
                    rank++;
                }
            }
        }
        System.out.println("Wrote TREC results to: " + outputFile);
    }

    // --------------------------
    // Internal evaluation functions
    // --------------------------

    // Compute Average Precision for one query (binary relevance: rel > 0)
    public static double averagePrecision(List<String> retrieved, Map<String, Integer> relevantMap) {
        int relevantCount = 0;
        int retrievedRelevant = 0;
        double sumPrecisions = 0.0;

        for (int i = 0; i < retrieved.size(); i++) {
            String docid = retrieved.get(i);
            if (relevantMap.getOrDefault(docid, 0) > 0) {
                retrievedRelevant++;
                double precAtI = retrievedRelevant / (double) (i + 1);
                sumPrecisions += precAtI;
            }
        }

        for (Integer rel: relevantMap.values()) if (rel > 0) relevantCount++;

        if (relevantCount == 0) return 0.0;
        return sumPrecisions / relevantCount;
    }

    // Compute precision, recall, f1 for a given retrieved list and qrel set
    public static double[] precisionRecallF1(List<String> retrieved, Map<String, Integer> relevantMap) {
        int tp = 0;
        int relevantTotal = 0;
        for (Integer rv : relevantMap.values()) if (rv > 0) relevantTotal++;
        for (String doc: retrieved) {
            if (relevantMap.getOrDefault(doc, 0) > 0) tp++;
        }
        int retrievedCount = retrieved.size();
        double precision = retrievedCount == 0 ? 0.0 : tp / (double) retrievedCount;
        double recall = relevantTotal == 0 ? 0.0 : tp / (double) relevantTotal;
        double f1 = (precision + recall) == 0.0 ? 0.0 : 2 * precision * recall / (precision + recall);
        return new double[]{precision, recall, f1};
    }

    public static double[] interpolatedPrecisionRecall(List<String> retrieved, Map<String, Integer> relevantMap) {
        // Build precision-recall curve at each rank
        List<Double> precisions = new ArrayList<>();
        List<Double> recalls = new ArrayList<>();
        int tp = 0;
        int relevantTotal = 0;
        for (Integer rv : relevantMap.values()) if (rv > 0) relevantTotal++;

        for (int i = 0; i < retrieved.size(); i++) {
            String docid = retrieved.get(i);
            if (relevantMap.getOrDefault(docid, 0) > 0) tp++;
            double prec = (i + 1) == 0 ? 0.0 : tp / (double) (i + 1);
            double rec = relevantTotal == 0 ? 0.0 : tp / (double) relevantTotal;
            precisions.add(prec);
            recalls.add(rec);
        }

        double[] interp = new double[11]; // 0.0,0.1,...1.0
        for (int j = 0; j <= 10; j++) {
            double recallLevel = j / 10.0;
            double maxPrec = 0.0;
            for (int i = 0; i < precisions.size(); i++) {
                if (recalls.get(i) >= recallLevel && precisions.get(i) > maxPrec) {
                    maxPrec = precisions.get(i);
                }
            }
            interp[j] = maxPrec;
        }
        return interp;
    }

    // Run internal evaluation over all queries: returns per-query metrics and aggregates
    public static void runInternalEvaluation(IndexSearcher searcher, Analyzer analyzer,
                                             String queriesFile, String qrelFile,
                                             int topK, float titleBoost, float bodyBoost) throws Exception {

        LinkedHashMap<String, String> queries = loadQueries(queriesFile);
        Map<String, Map<String, Integer>> qrels = loadQrels(qrelFile);
        MultiFieldQueryParser parser = makeParser(analyzer, titleBoost, bodyBoost);

        // accumulators
        double sumAP = 0.0;
        int qcount = 0;
        double sumPrecAtK = 0.0;
        double sumRecallAtK = 0.0;
        List<double[]> allInterpolated = new ArrayList<>();

        try (PrintWriter debug = new PrintWriter(new FileWriter("evaluation_details.txt"))) {
            for (Map.Entry<String, String> e : queries.entrySet()) {
                String qid = e.getKey();
                String qtext = e.getValue();
                Map<String, Integer> rels = qrels.getOrDefault(qid, Collections.emptyMap());

                String safeQuery = qtext.replaceAll("[\\?\\*]", " ");
                Query q = parser.parse(safeQuery);
                TopDocs topDocs = searcher.search(q, topK);
                // retrieved doc ids in order
                List<String> retrieved = new ArrayList<>();
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    retrieved.add(doc.get("id"));
                }

                // metrics
                double ap = averagePrecision(retrieved, rels);
                double[] prf = precisionRecallF1(retrieved, rels); // precision, recall, f1 (at retrieved size)
                double[] interp = interpolatedPrecisionRecall(retrieved, rels);

                sumAP += ap;
                sumPrecAtK += prf[0];
                sumRecallAtK += prf[1];
                allInterpolated.add(interp);
                qcount++;

                // debug per-query output
                debug.printf("Query %s: AP=%.4f, Precision=%.4f, Recall=%.4f, F1=%.4f\n", qid, ap, prf[0], prf[1], prf[2]);
            }
        }

        double MAP = qcount == 0 ? 0.0 : sumAP / qcount;
        double avgPrecision = qcount == 0 ? 0.0 : sumPrecAtK / qcount;
        double avgRecall = qcount == 0 ? 0.0 : sumRecallAtK / qcount;

        // compute mean interpolated precision at each level
        double[] meanInterp = new double[11];
        for (double[] arr : allInterpolated) {
            for (int i = 0; i < 11; i++) meanInterp[i] += arr[i];
        }
        for (int i = 0; i < 11; i++) if (qcount > 0) meanInterp[i] /= qcount;

        // print summary
        System.out.println("----- Internal Evaluation Summary -----");
        System.out.printf("Queries evaluated: %d\n", qcount);
        System.out.printf("MAP: %.6f\n", MAP);
        System.out.printf("Mean Precision (per-query): %.6f\n", avgPrecision);
        System.out.printf("Mean Recall (per-query): %.6f\n", avgRecall);
        System.out.println("11-point interpolated precision-recall (recall 0.0 .. 1.0):");
        for (int i = 0; i <= 10; i++) {
            System.out.printf("Recall=%.1f : Precision=%.6f\n", i / 10.0, meanInterp[i]);
        }
        System.out.println("Detailed per-query metrics saved to evaluation_details.txt");
    }

    public static IndexSearcher makeSearcherWithSimilarity(IndexReader reader, int simChoice) {
        IndexSearcher searcher = new IndexSearcher(reader);
        switch (simChoice) {
            case 1: // Classic TF-IDF
                searcher.setSimilarity(new ClassicSimilarity());
                break;
            case 3: // LM Dirichlet
                searcher.setSimilarity(new LMDirichletSimilarity(1500)); // mu default ~1500
                break;
            case 4: // LM Jelinek-Mercer
                searcher.setSimilarity(new LMJelinekMercerSimilarity(0.7f)); // lambda default 0.7
                break;
            default: // BM25
                searcher.setSimilarity(new BM25Similarity()); // default k1=1.2, b=0.75
                break;
        }
        return searcher;
    }

    public static void runAllCombinations(String cranFile, String queriesFile, String qrelsFile) throws Exception {
        List<Analyzer> analyzers = List.of(
                new StandardAnalyzer(),
                new EnglishAnalyzer(),
                new SimpleAnalyzer(),
                new WhitespaceAnalyzer()
        );

        String[] analyzerNames = {"StandardAnalyzer", "EnglishAnalyzer", "SimpleAnalyzer", "WhitespaceAnalyzer"};
        int[] similarities = {1, 2, 3, 4};
        String[] simNames = {"TFIDF", "BM25", "LMDirichlet", "LMJelinekMercer"};

        List<CranFieldDocument> docs = parseCranField(new File(cranFile));


        for (int i = 0; i < analyzers.size(); i++) {
            Analyzer analyzer = analyzers.get(i);
            String analyzerName = analyzerNames[i];

            for (int s = 0; s < similarities.length; s++) {
                int simChoice = similarities[s];
                String simName = simNames[s];

                String indexDirName = ROOT_INDEX_PATH + "index_" + analyzerName + "_" + simName;
                Path indexPath = Paths.get(indexDirName);

                System.out.println("\n=== Running combo: " + analyzerName + " + " + simName + " ===");

                // build index
                buildIndex(indexPath, docs, analyzer);

                // open searcher
                try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
                    IndexSearcher searcher = makeSearcherWithSimilarity(reader, simChoice);

                    String resultsPath =  "output/results";
                    File resultsFolder = new File(resultsPath);
                    if (!resultsFolder.exists()) resultsFolder.mkdirs();

                    // results file per combination
                    String resultFile = String.format(resultsPath+"/%s_%s_results.txt", analyzerName, simName);

                    String internalEvalPath =  "output/internalEval";
                    File evalFolder = new File(resultsPath);
                    if (!evalFolder.exists()) evalFolder.mkdirs();
                    String evalFile = String.format(internalEvalPath+"/%s_%s_eval.txt", analyzerName, simName);

                    // run internal evaluation
                    System.out.println("Running internal eval for " + analyzerName + " + " + simName);
                    System.out.printf("Indexing docs: %d | Analyzer: %s | Similarity: %s%n", docs.size(), analyzerName, simName);

                    generateTrecResults(searcher, analyzer, queriesFile, resultFile, DEFAULT_TOP_K, 2.0f, 1.0f);
                    runInternalEvaluation(searcher, analyzer, queriesFile, qrelsFile, DEFAULT_TOP_K, 2.0f, 1.0f);

                    // rename evaluation_details.txt to avoid overwriting
                    File evalDetails = new File("evaluation_details.txt");
                    if (evalDetails.exists()) {
                        evalDetails.renameTo(new File(evalFile));
                    }

                    String trecEvalPath =  "output/trec_eval";
                    File trecFolder = new File(trecEvalPath);
                    if (!trecFolder.exists()) trecFolder.mkdirs();

                    String trecEvalOutput = String.format(trecEvalPath+"/%s_%s_trec.txt", analyzerName, simName);
                    String internalEvalOutput = String.format(trecEvalPath+"/%s_%s_internal.txt", analyzerName, simName);

                    File trecEvalDetail = new File("evaluation_details.txt");
                    if (trecEvalDetail.exists()) {
                        trecEvalDetail.renameTo(new File(internalEvalOutput));
                    }
                    ProcessBuilder pb = new ProcessBuilder("trec_eval", qrelsFile, resultFile);
                    Process p = pb.start();

                    try (BufferedReader readerCmd = new BufferedReader(new InputStreamReader(p.getInputStream()));
                         PrintWriter writer = new PrintWriter(new FileWriter(trecEvalOutput))) {
                        String line;
                        while ((line = readerCmd.readLine()) != null) {
                            writer.println(line);
                        }
                    }
                    p.waitFor();
                    System.out.println("TREC_EVAL done: " + trecEvalOutput);
                }
            }
        }

        System.out.println("\n🎯 All analyzer × similarity combinations completed. Check results/ and eval_trec/ folders.");
    }

    // --------------------------
    // Main program flow & menu
    // --------------------------
    public static void main(String[] args) throws Exception {
        File cran = new File(CRAN_FILE);
        if (!cran.exists()) {
            System.err.println("ERROR: cran.all not found in project root!");
            System.err.println("Download from: http://ir.dcs.gla.ac.uk/resources/test_collections/cran/");
            return;
        }

        Scanner sc = new Scanner(System.in);

        mainLoop:
        while (true) {
            System.out.println("\n--- Menu ---");
            System.out.println("1: Interactive search");
            System.out.println("2: Run all Analyzer+Similarity Evaluation");
            System.out.println("3: Exit");
            System.out.print("> ");
            String choice = sc.nextLine().trim();
            switch (choice) {
                case "1":
                    // Choose analyzer & index
                    Analyzer analyzer = chooseAnalyzer(sc);
                    System.out.println("Parsing cran.all ...");
                    List<CranFieldDocument> docs = parseCranField(cran);
                    System.out.println("Parsed documents: " + docs.size());
                    System.out.println("Building index using analyzer: " + analyzer.getClass().getSimpleName());
                    buildIndex(INTERACTIVE_INDEX_PATH, docs, analyzer);
                    System.out.println("Index built at: " + INTERACTIVE_INDEX_PATH.toAbsolutePath());

                    // interactive configuration: boosts and similarity
                    System.out.print("Title boost (default 2.0) : ");
                    String tb = sc.nextLine().trim();
                    float titleBoost = tb.isEmpty() ? 2.0f : Float.parseFloat(tb);

                    System.out.print("Body boost (default 1.0)  : ");
                    String bb = sc.nextLine().trim();
                    float bodyBoost = bb.isEmpty() ? 1.0f : Float.parseFloat(bb);

                    System.out.println("Choose Scoring Method (default 2 = BM25):");
                    System.out.println("1: TF-IDF (ClassicSimilarity)");
                    System.out.println("2: BM25 (BM25Similarity)");
                    System.out.println("3: LM Dirichlet (LMDirichletSimilarity)");
                    System.out.println("4: LM Jelinek-Mercer (LMJelinekMercerSimilarity)");
                    System.out.print("> ");
                    String simChoiceStr = sc.nextLine().trim();
                    int simChoice = simChoiceStr.isEmpty() ? 2 : Integer.parseInt(simChoiceStr);

                    try (IndexReader reader = DirectoryReader.open(FSDirectory.open(INTERACTIVE_INDEX_PATH))) {
                        IndexSearcher searcher = makeSearcherWithSimilarity(reader, simChoice);
                        interactiveSearch(searcher, analyzer, sc, titleBoost, bodyBoost);

                        generateTrecResults(searcher, analyzer, "cran/cran.qry", "output/interactive/results.txt", 100, titleBoost, bodyBoost);

                    }

                    break;
                case "2":
                    runAllCombinations(CRAN_FILE, "cran/cran.qry", "cran/cranqrel");
                    break;
                default:
                    break mainLoop;
            }

        }

        System.out.println("Done. Exiting.");
    }
}
