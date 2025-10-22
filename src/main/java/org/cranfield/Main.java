package org.cranfield;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.*;
import org.apache.lucene.queryparser.classic.*;
import org.apache.lucene.util.Version;

public class Main {

    // default file names - change or pass via args
    private static String CRAN_PATH = "cran/cran.all.1400";
    private static String QUERIES_PATH = "cran/cran.qry";
    private static String QRELS_PATH = "cran/cranqrel";
    private static String INDEX_DIR = "index";
    private static String RESULTS_FILE = "results.txt";
    private static String RUN_TAG = "myRun";

    // Top-K to retrieve per query
    private static final int TOP_K = 50;

    // public static void main(String[] args) throws Exception {
    //     // Simple arg parsing

    //     Map<String, String> amap = parseArgs(args);
    //     if (amap.containsKey("cran")) CRAN_PATH = amap.get("cran");
    //     if (amap.containsKey("queries")) QUERIES_PATH = amap.get("queries");
    //     if (amap.containsKey("qrels")) QRELS_PATH = amap.get("qrels");
    //     if (amap.containsKey("index")) INDEX_DIR = amap.get("index");
    //     String similarityChoice = amap.getOrDefault("similarity", "bm25").toLowerCase();
    //     String analyzerChoice = amap.getOrDefault("analyzer", "english").toLowerCase();

    //     System.out.println("Cranfile: " + CRAN_PATH);
    //     System.out.println("Queries: " + QUERIES_PATH);
    //     System.out.println("Qrels: " + QRELS_PATH);
    //     System.out.println("Index dir: " + INDEX_DIR);
    //     System.out.println("Similarity: " + similarityChoice);
    //     System.out.println("Analyzer: " + analyzerChoice);

    //     // Choose analyzer
    //     Analyzer analyzer;
    //     switch (analyzerChoice) {
    //         case "standard":
    //             analyzer = new StandardAnalyzer();
    //             break;
    //         case "english":
    //         default:
    //             analyzer = new EnglishAnalyzer(); // includes stopwords + Porter stemming
    //             break;
    //     }

    //     // Parse documents
    //     List<DocStruct> docs = parseCranfieldDocument(CRAN_PATH);
    //     System.out.printf("Parsed %d documents from Cranfield.\n", docs.size());

    //     // Index documents
    //     indexDocuments(docs, INDEX_DIR, analyzer);

    //     // Parse queries and qrels
    //     Map<String, String> queries = parseCranfieldQueries(QUERIES_PATH);
    //     System.out.printf("Parsed %d queries.\n", queries.size());

    //     Map<String, Set<String>> qrels = parseQrels(QRELS_PATH);
    //     System.out.printf("Parsed qrels for %d queries.\n", qrels.size());

    //     // Prepare searcher
    //     Directory dir = FSDirectory.open(Paths.get(INDEX_DIR));
    //     DirectoryReader reader = DirectoryReader.open(dir);
    //     IndexSearcher searcher = new IndexSearcher(reader);

    //     // Set similarity
    //     if (similarityChoice.equals("bm25")) {
    //         searcher.setSimilarity(new BM25Similarity());
    //     } else if (similarityChoice.equals("tfidf") || similarityChoice.equals("vsm")) {
    //         searcher.setSimilarity(new ClassicSimilarity()); // Lucene's TF-IDF implementation
    //     } else {
    //         System.out.println("Unknown similarity, using BM25.");
    //         searcher.setSimilarity(new BM25Similarity());
    //     }

    //     // Multi-field query over "title" and "body" (we index both)
    //     String[] searchFields = new String[] { "title", "body" };
    //     MultiFieldQueryParser mfqp = new MultiFieldQueryParser(searchFields, analyzer);

    //     // Results file to write TREC formatted lines
    //     BufferedWriter resultsWriter = new BufferedWriter(new FileWriter(RESULTS_FILE));

    //     // For MAP & recall calculation
    //     double sumAvgPrecision = 0.0;
    //     int queriesWithAtLeastOneRelevant = 0;
    //     double sumRecallAt50 = 0.0;
    //     int processedQueries = 0;

    //     List<String> sortedQueryIds = new ArrayList<>(queries.keySet());
    //     Collections.sort(sortedQueryIds, Comparator.comparingInt(Integer::parseInt)); // ensure numeric order

    //     for (String qid : sortedQueryIds) {
    //         processedQueries++;
    //         String qtext = queries.get(qid);
    //         if (qtext == null || qtext.trim().isEmpty()) continue;

    //         Query luceneQuery = mfqp.parse(QueryParser.escape(qtext));

    //         TopDocs topDocs = searcher.search(luceneQuery, TOP_K);
    //         ScoreDoc[] hits = topDocs.scoreDocs;

    //         // write TREC-format results: query_id Q0 docno rank score myRunTag
    //         for (int rank = 0; rank < hits.length; rank++) {
    //             Document doc = searcher.storedFields().document(hits[rank].doc);
    //             String docno = doc.get("id");
    //             float score = hits[rank].score;
    //             // TREC format
    //             String line = String.format("%s Q0 %s %d %.6f %s", qid, docno, rank+1, score, RUN_TAG);
    //             resultsWriter.write(line);
    //             resultsWriter.newLine();
    //         }

    //         // Compute Average Precision for this query using qrels
    //         Set<String> relevant = qrels.getOrDefault(qid, Collections.emptySet());
    //         if (relevant.size() > 0) {
    //             queriesWithAtLeastOneRelevant++;
    //         }

    //         // iterate retrieved and accumulate for AP
    //         int numRelRetrieved = 0;
    //         double sumPrecisionAtRel = 0.0;
    //         for (int rank = 0; rank < hits.length; rank++) {
    //             Document doc = searcher.storedFields().document(hits[rank].doc);
    //             String docno = doc.get("id");
    //             if (relevant.contains(docno)) {
    //                 numRelRetrieved++;
    //                 double precisionAtK = (double) numRelRetrieved / (rank + 1);
    //                 sumPrecisionAtRel += precisionAtK;
    //             }
    //         }
    //         double avgPrecision = 0.0;
    //         if (relevant.size() > 0) {
    //             avgPrecision = sumPrecisionAtRel / (double) relevant.size();
    //         }
    //         sumAvgPrecision += avgPrecision;

    //         // Recall@50 = number of relevant in top50 / total relevant for that query
    //         double recallAt50 = 0.0;
    //         if (relevant.size() > 0) {
    //             int relInTopK = 0;
    //             for (ScoreDoc sd : hits) {
    //                 Document doc = searcher.storedFields().document(sd.doc);
    //                 if (relevant.contains(doc.get("id"))) relInTopK++;
    //             }
    //             recallAt50 = (double) relInTopK / (double) relevant.size();
    //             sumRecallAt50 += recallAt50;
    //         }
    //     }

    //     resultsWriter.close();
    //     reader.close();
    //     dir.close();

    //     double MAP = sumAvgPrecision / (double) processedQueries;
    //     double meanRecallAt50 = sumRecallAt50 / (double) processedQueries; // note: queries without qrels counted as 0 recall

    //     System.out.println("==== Evaluation (computed in Java) ====");
    //     System.out.printf("Processed queries: %d\n", processedQueries);
    //     System.out.printf("MAP (over all queries) = %.6f\n", MAP);
    //     System.out.printf("Mean Recall@%d = %.6f\n", TOP_K, meanRecallAt50);
    //     System.out.println("TREC-style results written to: " + RESULTS_FILE);
    //     System.out.println("You can run trec_eval externally: ./trec_eval <qrels> " + RESULTS_FILE);
    // }

    public static void main(String[] args) throws Exception {

    // Define analyzers
    Map<String, Analyzer> analyzers = new LinkedHashMap<>();
    analyzers.put("standard", new StandardAnalyzer());
    analyzers.put("english", new EnglishAnalyzer());
    analyzers.put("whitespace", new org.apache.lucene.analysis.core.WhitespaceAnalyzer());

    // Define similarities
    Map<String, Similarity> similarities = new LinkedHashMap<>();
    similarities.put("bm25", new BM25Similarity());
    similarities.put("tfidf", new ClassicSimilarity());
    similarities.put("dirichlet", new LMDirichletSimilarity());

    // Parse documents once
    List<DocStruct> docs = parseCranfieldDocument(CRAN_PATH);
    System.out.printf("Parsed %d Cranfield documents.\n", docs.size());

    // Parse queries & qrels once
    Map<String, String> queries = parseCranfieldQueries(QUERIES_PATH);
    Map<String, Set<String>> qrels = parseQrels(QRELS_PATH);

    // Loop over each analyzer-similarity combination
    for (String analyzerName : analyzers.keySet()) {
        for (String simName : similarities.keySet()) {

            Analyzer analyzer = analyzers.get(analyzerName);
            Similarity similarity = similarities.get(simName);

            String comboTag = analyzerName + "_" + simName;
            String indexDir = INDEX_DIR + "_" + comboTag;
            String resultFile = "results_" + comboTag + ".txt";
            String runTag = comboTag;

            System.out.println("\n==============================");
            System.out.printf("Running combination: %s + %s\n", analyzerName, simName);
            System.out.println("==============================");

            // 1. Index documents
            indexDocuments(docs, indexDir, analyzer);

            // 2. Search and evaluate
            evaluateCombination(indexDir, analyzer, similarity, queries, qrels, resultFile, runTag);
        }
    }

    System.out.println("\n✅ All combinations completed. Check generated results_*.txt files.");
}

private static void runTrecEval(String qrelsPath, String resultFile) {
    try {
        // Change this to the full path to your trec_eval executable
        String trecEvalCmd = "/opt/trec_eval/trec_eval";;  

        ProcessBuilder pb = new ProcessBuilder(trecEvalCmd, qrelsPath, resultFile);
        pb.redirectErrorStream(true); // combine stdout and stderr
        Process process = pb.start();

        // Read and print trec_eval output
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        System.out.println("\n--- TREC Eval Output for " + resultFile + " ---");
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.err.println("trec_eval exited with code: " + exitCode);
        }
        System.out.println("--- End of TREC Eval ---\n");

    } catch (Exception e) {
        e.printStackTrace();
    }
}


// --- Evaluate one analyzer + similarity combo ---
private static void evaluateCombination(String indexDir, Analyzer analyzer, Similarity sim,
                                        Map<String, String> queries,
                                        Map<String, Set<String>> qrels,
                                        String resultFile, String runTag) throws Exception {

    Directory dir = FSDirectory.open(Paths.get(indexDir));
    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);
    searcher.setSimilarity(sim);

    MultiFieldQueryParser parser = new MultiFieldQueryParser(new String[]{"title", "body"}, analyzer);
    BufferedWriter resultsWriter = new BufferedWriter(new FileWriter(resultFile));

    double sumAvgPrecision = 0.0;
    double sumRecallAt50 = 0.0;
    int queryCount = 0;

    for (String qid : new TreeSet<>(queries.keySet())) {
        String qtext = queries.get(qid);
        if (qtext == null || qtext.isEmpty()) continue;
        Query query = parser.parse(QueryParser.escape(qtext));
        TopDocs topDocs = searcher.search(query, 50);
        ScoreDoc[] hits = topDocs.scoreDocs;

        // Write TREC-style output
        for (int rank = 0; rank < hits.length; rank++) {
            // Document doc = searcher.getIndexReader().document(hits[rank].doc);
            Document doc = searcher.storedFields().document(hits[rank].doc);
            String docno = doc.get("id");
            resultsWriter.write(String.format("%s Q0 %s %d %.6f %s\n",
                    qid, docno, rank + 1, hits[rank].score, runTag));
        }

        // Compute evaluation
        Set<String> relevant = qrels.getOrDefault(qid, Collections.emptySet());
        if (!relevant.isEmpty()) {
            queryCount++;
            int relRetrieved = 0;
            double sumPrecAtRel = 0.0;
            for (int rank = 0; rank < hits.length; rank++) {
                // Document doc = searcher.getIndexReader().document(hits[rank].doc);
                Document doc = searcher.storedFields().document(hits[rank].doc);
                if (relevant.contains(doc.get("id"))) {
                    relRetrieved++;
                    sumPrecAtRel += (double) relRetrieved / (rank + 1);
                }
            }
            sumAvgPrecision += (relevant.isEmpty() ? 0 : sumPrecAtRel / relevant.size());
            long relInTop50 = Arrays.stream(hits)
                    .filter(sd -> {
                        try {
                            return relevant.contains(searcher.storedFields().document(sd.doc).get("id"));
                        } catch (IOException e) { return false; }
                    }).count();
            sumRecallAt50 += (double) relInTop50 / relevant.size();
        }
    }

    resultsWriter.close();
    reader.close();
    dir.close();

    double MAP = (queryCount == 0) ? 0 : sumAvgPrecision / queryCount;
    double meanRecall = (queryCount == 0) ? 0 : sumRecallAt50 / queryCount;

    System.out.printf("Combo %-20s | MAP = %.4f | Recall@50 = %.4f | Results: %s\n",
            runTag, MAP, meanRecall, resultFile);
        
    // --- Run trec_eval automatically ---
    runTrecEval(QRELS_PATH, resultFile);
}


    // -----------------------------
    // Indexing
    // -----------------------------
    private static void indexDocuments(List<DocStruct> docs, String indexDir, Analyzer analyzer) throws IOException {
        Path indexPath = Paths.get(indexDir);
        if (!Files.exists(indexPath)) {
            Files.createDirectories(indexPath);
        }
        Directory directory = FSDirectory.open(indexPath);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        IndexWriter writer = new IndexWriter(directory, config);

        System.out.println("Indexing documents...");
        int count = 0;
        for (DocStruct d : docs) {
            Document doc = new Document();
            // id: store as StringField? We need it searchable as exact identifier; store it and keep searchable
            // Use TextField for consistency with user's earlier code, but better is StringField. We want to sort/compare exact numbers,
            // but queries will search title/body. We will use StringField for id.
            doc.add(new StringField("id", d.id, Field.Store.YES));
            doc.add(new TextField("title", d.title == null ? "" : d.title, Field.Store.YES));
            doc.add(new TextField("author", d.author == null ? "" : d.author, Field.Store.YES));
            doc.add(new TextField("bib", d.bib == null ? "" : d.bib, Field.Store.YES));
            doc.add(new TextField("body", d.body == null ? "" : d.body, Field.Store.YES));
            writer.addDocument(doc);

            count++;
            if (count % 200 == 0) System.out.printf("  indexed %d docs...\n", count);
        }
        writer.close();
        directory.close();
        System.out.println("Indexing complete. Total indexed: " + docs.size());
    }

    // -----------------------------
    // Parse Cranfield doc file (cran.all.1400)
    // -----------------------------
    private static List<DocStruct> parseCranfieldDocument(String cranfieldPath) throws IOException {
        List<DocStruct> docs = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(cranfieldPath));

        String id = "", title = "", author = "", bib = "", body = "";
        String currentTag = "";

        for (String rawLine : lines) {
            String line = rawLine;
            if (line.startsWith(".I")) {
                // save previous
                if (!id.isEmpty()) {
                    docs.add(new DocStruct(id.trim(), title.trim(), author.trim(), bib.trim(), body.trim()));
                    title = author = bib = body = "";
                }
                id = line.substring(3).trim(); // after ".I "
                currentTag = "";
            } else if (line.startsWith(".T")) {
                currentTag = "T";
            } else if (line.startsWith(".A")) {
                currentTag = "A";
            } else if (line.startsWith(".B")) {
                currentTag = "B";
            } else if (line.startsWith(".W")) {
                currentTag = "W";
            } else {
                // content line
                if (currentTag.equals("T")) {
                    title += line + " ";
                } else if (currentTag.equals("A")) {
                    author += line + " ";
                } else if (currentTag.equals("B")) {
                    bib += line + " ";
                } else if (currentTag.equals("W")) {
                    body += line + " ";
                } // else ignore
            }
        }
        // last doc
        if (!id.isEmpty()) {
            docs.add(new DocStruct(id.trim(), title.trim(), author.trim(), bib.trim(), body.trim()));
        }
        return docs;
    }

    // -----------------------------
    // Parse queries file cran.qry
    // Format example:
    // .I 1
    // .W
    // what is similarity ...
    // .I 2
    // ...
    // -----------------------------
    private static Map<String, String> parseCranfieldQueries(String qpath) throws IOException {
        // LinkedHashMap<String, String> queries = new LinkedHashMap<>();
        // int id = 1; // start ID from 1

        // try (BufferedReader br = new BufferedReader(new FileReader(queriesFile))) {
        //     String line;
        //     boolean inW = false;
        //     StringBuilder sb = new StringBuilder();

        //     while ((line = br.readLine()) != null) {
        //         line = line.trim();
        //         if (line.startsWith(".I")) {
        //             if (!sb.isEmpty()) {
        //                 queries.put(String.valueOf(id), sb.toString().trim());
        //                 id++; // increment ID for the next query
        //             }
        //             sb.setLength(0);
        //             inW = false;
        //         } else if (line.equals(".W")) {
        //             inW = true;
        //         } else if (inW) {
        //             sb.append(line).append(' ');
        //         }
        //     }

        //     if (!sb.isEmpty()) {
        //         queries.put(String.valueOf(id), sb.toString().trim());
        //     }
        // }
        // return queries;




        // Map<String, String> queries = new HashMap<>();
        // List<String> lines = Files.readAllLines(Paths.get(qpath));

        // String currentId = null;
        // String currentTag = "";
        // StringBuilder currentText = new StringBuilder();
        // Integer i = 1;
        // for (String raw : lines) {
        //     String line = raw;
        //     if (line.startsWith(".I")) {
        //         if (currentId != null) {
        //             queries.put(String.valueOf(i), currentText.toString().trim());
        //             currentText.setLength(0);
        //         }
        //         currentId = line.substring(3).trim();
        //         currentTag = "";
        //         i++;
        //     } else if (line.startsWith(".W")) {
        //         currentTag = "W";
        //     } else {
        //         if ("W".equals(currentTag) && currentId != null) {
        //             currentText.append(line).append(" ");
        //         }
        //     }
        // }
        // if (currentId != null) {
        //     queries.put(String.valueOf(i), currentText.toString().trim());
        // }
        // return queries;

        LinkedHashMap<String, String> queries = new LinkedHashMap<>();
    int id = 1; // start ID from 1

    try (BufferedReader br = new BufferedReader(new FileReader(qpath))) {
        String line;
        boolean inW = false;
        StringBuilder sb = new StringBuilder();

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.startsWith(".I")) {
                if (sb.length() > 0) {
                    queries.put(String.valueOf(id), sb.toString().trim());
                    id++; // increment ID for next query
                }
                sb.setLength(0);
                inW = false;
            } else if (line.equals(".W")) {
                inW = true;
            } else if (inW) {
                sb.append(line).append(' ');
            }
        }

        // add last query
        if (sb.length() > 0) {
            queries.put(String.valueOf(id), sb.toString().trim());
        }
    }

    return queries;
    }

    // -----------------------------
    // Parse qrels: format "qid docno rel" (space separated)
    // We'll store for each qid the set of relevant docnos (strings)
    // -----------------------------
    private static Map<String, Set<String>> parseQrels(String qrelPath) throws IOException {
        Map<String, Set<String>> qrels = new HashMap<>();
        List<String> lines = Files.readAllLines(Paths.get(qrelPath));
        for (String l : lines) {
            l = l.trim();
            if (l.isEmpty()) continue;
            String[] parts = l.split("\\s+");
            if (parts.length < 3) continue;
            String qid = parts[0];
            String docno = parts[1];
            // String rel = parts[2]; // relevance code (1-5) but we treat all present as relevant
            qrels.computeIfAbsent(qid, k -> new HashSet<>()).add(docno);
        }
        return qrels;
    }

    // -----------------------------
    // Small arg parser
    // -----------------------------
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> amap = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i+1].startsWith("--")) {
                    amap.put(key, args[i+1]);
                    i++;
                } else {
                    amap.put(key, "true");
                }
            }
        }
        return amap;
    }

    // -----------------------------
    // Document container
    // -----------------------------
    private static class DocStruct {
        String id;
        String title;
        String author;
        String bib;
        String body;
        DocStruct(String id, String title, String author, String bib, String body) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.bib = bib;
            this.body = body;
        }
    }
}
