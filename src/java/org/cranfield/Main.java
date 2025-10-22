// package ie.tcd.dalyc24;

// import java.io.IOException;

// import java.nio.file.Paths;

// import org.apache.lucene.analysis.Analyzer;
// import org.apache.lucene.analysis.standard.StandardAnalyzer;
// import org.apache.lucene.document.Document;
// import org.apache.lucene.document.Field;
// import org.apache.lucene.document.TextField;
// import org.apache.lucene.index.IndexWriter;
// import org.apache.lucene.index.IndexWriterConfig;
// import org.apache.lucene.store.Directory;
// import org.apache.lucene.store.FSDirectory;
// // import org.apache.lucene.store.RAMDirectory;
 
// public class Cranfield
// {
    
//     // Directory where the search index will be saved
//     private static String INDEX_DIRECTORY = "../index";
//     private static String CRAN = "cran/cran/cran.all";

//     public 
//     public static void parseCrandfieldDocument(String cranfieldPath){
//     // make list of object of class documentStruc
    

//     }

//     public static void main(String[] args) throws IOException
//     {
//         // Analyzer that is used to process TextField
//         Analyzer analyzer = new StandardAnalyzer();
        
//         // To store an index in memory
//         // Directory directory = new RAMDirectory();
//         // To store an index on disk
//         Directory directory = FSDirectory.open(Paths.get(INDEX_DIRECTORY));
//         IndexWriterConfig config = new IndexWriterConfig(analyzer);
        
//         // Index opening mode
//         // IndexWriterConfig.OpenMode.CREATE = create a new index
//         // IndexWriterConfig.OpenMode.APPEND = open an existing index
//         // IndexWriterConfig.OpenMode.CREATE_OR_APPEND = create an index if it
//         // does not exist, otherwise it opens it
//         config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        
//         IndexWriter iwriter = new IndexWriter(directory, config);  
        
//         // Create a new document
//         // Document doc = new Document();
//         // doc.add(new TextField("super_name", "Spider-MAN1", Field.Store.YES));
//         // doc.add(new TextField("name", "Peter ParkER1", Field.Store.YES));
//         // doc.add(new TextField("category", "superheRO0", Field.Store.YES));

//         for (String arg : args)
// 		{
// 			// Load the contents of the file
// 			System.out.printf("Indexing \"%s\"\n", arg);
// 			String content = new String(Files.readAllBytes(Paths.get(arg)));

// 			// Create a new document and add the file's contents
// 			Document doc = new Document();
// 			doc.add(new StringField("filename", arg, Field.Store.YES));
// 			doc.add(new TextField("content", content, Field.Store.YES));

// 			// Add the file to our linked list
// 			documents.add(doc);
// 		}

// 		// Write all the documents in the linked list to the search index
// 		iwriter.addDocuments(documents);


//         // Save the document to the index
//         iwriter.addDocument(doc);

//         // Commit changes and close everything
//         iwriter.close();
//         directory.close();
//     }
// }

package ie.tcd.dalyc24;

import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

public class Main {

    // Directory where the search index will be saved
    private static String INDEX_DIRECTORY = "../index";
    private static String CRAN = "cran/cran.all.1400";

    // -----------------------------
    // Parse Cranfield Dataset
    // -----------------------------
    public static List<documentStruc> parseCranfieldDocument(String cranfieldPath) {
        List<documentStruc> docs = new ArrayList<>();

        try {
            List<String> lines = Files.readAllLines(Paths.get(cranfieldPath));
            String id = "", title = "", author = "", bib = "", body = "";
            String currentTag = "";

            for (String line : lines) {
                if (line.startsWith(".I")) {
                    // Save previous doc
                    if (!id.isEmpty()) {
                        docs.add(new documentStruc(id, title.trim(), author.trim(), bib.trim(), body.trim()));
                        title = author = bib = body = "";
                    }
                    id = line.substring(3).trim();
                } else if (line.startsWith(".T")) {
                    currentTag = "T";
                } else if (line.startsWith(".A")) {
                    currentTag = "A";
                } else if (line.startsWith(".B")) {
                    currentTag = "B";
                } else if (line.startsWith(".W")) {
                    currentTag = "W";
                } else {
                    switch (currentTag) {
                        case "T": title += line + " "; break;
                        case "A": author += line + " "; break;
                        case "B": bib += line + " "; break;
                        case "W": body += line + " "; break;
                    }
                }
            }

            // Add the last document
            if (!id.isEmpty()) {
                docs.add(new documentStruc(id, title.trim(), author.trim(), bib.trim(), body.trim()));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return docs;
    }

    // -----------------------------
    // Main: Index Cranfield Documents
    // -----------------------------
    public static void main(String[] args) throws IOException {
        Analyzer analyzer = new StandardAnalyzer();
        Directory directory = FSDirectory.open(Paths.get(INDEX_DIRECTORY));
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        IndexWriter iwriter = new IndexWriter(directory, config);

        // Parse Cranfield dataset
        List<documentStruc> docs = parseCranfieldDocument(CRAN);

        for (documentStruc d : docs) {
            Document doc = new Document();
            doc.add(new TextField("id", d.getId(), Field.Store.YES));
            doc.add(new TextField("title", d.getTitle(), Field.Store.YES));
            doc.add(new TextField("author", d.getAuthor(), Field.Store.YES));
            doc.add(new TextField("bib", d.getBib(), Field.Store.YES));
            doc.add(new TextField("body", d.getBody(), Field.Store.YES));
            iwriter.addDocument(doc);
        }

        iwriter.close();
        directory.close();

        System.out.println("Indexed " + docs.size() + " Cranfield documents.");
    }

    // -----------------------------
    // Inner Class: documentStruc
    // -----------------------------
    public static class documentStruc {
        private String id;
        private String title;
        private String author;
        private String bib;
        private String body;

        public documentStruc(String id, String title, String author, String bib, String body) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.bib = bib;
            this.body = body;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getAuthor() { return author; }
        public String getBib() { return bib; }
        public String getBody() { return body; }
    }
}
