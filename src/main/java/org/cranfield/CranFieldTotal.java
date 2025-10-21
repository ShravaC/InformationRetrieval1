package org.cranfield;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.*;
import org.apache.lucene.store.FSDirectory;

public class CranFieldTotal {

    private static final Path INDEX_PATH = Paths.get("index");
    private static final String CRAN_FILE = "cran/cran.all.1400";
    private static final String CRAN_QUERY_FILE = "cran/cran.qry";

    public static class CranFieldDocument {
        public String id, title = "", author = "", biblio = "", body = "";
    }

    // ------------------ Parse cran.all ------------------
    public static List<CranFieldDocument> parseCranfield(File file) throws IOException {
        List<CranFieldDocument> docs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            CranFieldDocument cur = null;
            String section = null;
            StringBuilder sb = new StringBuilder();

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(".I ")) {
                    if (cur != null) assignSection(cur, section, sb.toString().trim(), docs);
                    cur = new CranFieldDocument();
                    cur.id = line.substring(3).trim();
                    section = null;
                    sb.setLength(0);
                } else if (line.equals(".T") || line.equals(".A") || line.equals(".B") || line.equals(".W")) {
                    if (cur != null && section != null) assignSection(cur, section, sb.toString().trim(), null);
                    section = line.substring(1);
                    sb.setLength(0);
                } else if (section != null) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(line);
                }
            }
            if (cur != null) assignSection(cur, section, sb.toString().trim(), docs);
        }
        return docs;
    }

    private static void assignSection(CranFieldDocument doc, String section, String text, List<CranFieldDocument> docs) {
        if (section == null) return;
        switch (section) {
            case "T" -> doc.title = text;
            case "A" -> doc.author = text;
            case "B" -> doc.biblio = text;
            case "W" -> doc.body = text;
        }
        if (docs != null) docs.add(doc);
    }

    // ------------------ Analyzer choice ------------------
    public static Analyzer chooseAnalyzer(Scanner sc) {
        System.out.println("Choose Analyzer (default = EnglishAnalyzer):");
        System.out.println("1: Standard Analyzer\n2: English Analyzer\n3: Simple Analyzer\n4: Whitespace Analyzer\n> ");
        String input = sc.nextLine().trim();
        return switch (input) {
            case "1" -> new StandardAnalyzer();
            case "3" -> new SimpleAnalyzer();
            case "4" -> new WhitespaceAnalyzer();
            default -> new EnglishAnalyzer();
        };
    }

    // ------------------ Build Lucene index ------------------
    public static void buildIndex(List<CranFieldDocument> docs, Analyzer analyzer) throws IOException {
        try (IndexWriter writer = new IndexWriter(FSDirectory.open(INDEX_PATH),
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            for (CranFieldDocument cd : docs) {
                Document d = new Document();
                d.add(new StringField("id", cd.id, Field.Store.YES));
                d.add(new TextField("title", cd.title, Field.Store.YES));
                d.add(new TextField("author", cd.author, Field.Store.YES));
                d.add(new TextField("biblio", cd.biblio, Field.Store.NO));
                d.add(new TextField("body", cd.body, Field.Store.YES));
                writer.addDocument(d);
            }
        }
    }

    // ------------------ Parse cran.qry ------------------
    public static Map<String, String> parseQueries(File file) throws IOException {
        Map<String, String> queries = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            String id = null;
            StringBuilder sb = new StringBuilder();
            boolean inQuery = false;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(".I")) {
                    if (id != null) {
                        queries.put(id, sb.toString().trim());
                        sb.setLength(0);
                    }
                    id = line.substring(3).trim();
                    inQuery = false;
                } else if (line.equals(".W")) {
                    inQuery = true;
                } else if (inQuery) {
                    sb.append(line).append(' ');
                }
            }
            if (id != null) queries.put(id, sb.toString().trim());
        }
        return queries;
    }

    // ------------------ Run queries automatically ------------------
    public static void runQueries(IndexSearcher searcher, Analyzer analyzer, File qryFile) throws IOException, ParseException {
        Map<String, String> queries = parseQueries(qryFile);
        String[] fields = {"title", "body"};
        Map<String, Float> boosts = Map.of("title", 2.0f, "body", 1.0f);
        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);

        Path resultsPath = Paths.get("results.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(resultsPath)) {
            for (Map.Entry<String, String> entry : queries.entrySet()) {
                String qid = entry.getKey();
                String qText = entry.getValue();
                Query q = parser.parse(qText);
                TopDocs topDocs = searcher.search(q, 50);

                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    String docId = doc.get("id");
                    float score = sd.score;

                    // Write to results file in TREC format
                    writer.write(String.format("%s Q0 %s 0 %.6f STANDARD%n", qid, docId, score));
                }
            }
        }
        System.out.println("Queries run complete. Results saved to: " + resultsPath.toAbsolutePath());
    }

    // ------------------ Interactive search ------------------
    public static void interactiveSearch(IndexSearcher searcher, Analyzer analyzer, Scanner sc) throws IOException, ParseException {
        String[] fields = {"title", "body"};
        Map<String, Float> boosts = Map.of("title", 2.0f, "body", 1.0f);
        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);

        System.out.println("Type ':q' to quit");
        while (true) {
            System.out.print("\nQuery: ");
            String qText = sc.nextLine();
            if (qText.equals(":q")) break;

            System.out.print("Filter by author (optional): ");
            String authorFilter = sc.nextLine().trim();
            System.out.print("Filter by title (optional): ");
            String titleFilter = sc.nextLine().trim();

            Query q = parser.parse(qText);
            BooleanQuery.Builder combined = new BooleanQuery.Builder();
            combined.add(q, BooleanClause.Occur.MUST);
            if (!authorFilter.isEmpty()) combined.add(new TermQuery(new Term("author", authorFilter.toLowerCase())), BooleanClause.Occur.FILTER);
            if (!titleFilter.isEmpty()) combined.add(new TermQuery(new Term("title", titleFilter.toLowerCase())), BooleanClause.Occur.FILTER);

            TopDocs topDocs = searcher.search(combined.build(), 10);
            System.out.println("Total hits: " + topDocs.totalHits);

            for (int i = 0; i < topDocs.scoreDocs.length; i++) {
                ScoreDoc sd = topDocs.scoreDocs[i];
                Document doc = searcher.storedFields().document(sd.doc); // ✅ correct in Lucene 10
                String snippet = doc.get("body");
                if (snippet != null && snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                System.out.printf("%2d. id=%s score=%.4f title=%s\n    %s\n", i + 1, doc.get("id"), sd.score, doc.get("title"), snippet);
            }
        }
    }

    // ------------------ Main ------------------
    public static void main(String[] args) throws Exception {
        File file = new File(CRAN_FILE);
        if (!file.exists()) { System.err.println("File not found: "+CRAN_FILE); return; }

        Scanner sc = new Scanner(System.in);
        Analyzer analyzer = chooseAnalyzer(sc);
        List<CranFieldDocument> docs = parseCranfield(file);
        buildIndex(docs, analyzer);
        System.out.println("Index built at: " + INDEX_PATH.toAbsolutePath());

        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(INDEX_PATH))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            // default similarity
            searcher.setSimilarity(new BM25Similarity());
            interactiveSearch(searcher, analyzer, sc);
        }
    }
}
