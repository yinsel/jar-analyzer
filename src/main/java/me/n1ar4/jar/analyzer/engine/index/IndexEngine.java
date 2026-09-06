/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.engine.index;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import me.n1ar4.jar.analyzer.engine.index.entity.Result;
import me.n1ar4.jar.analyzer.gui.LuceneSearchForm;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.lucene50.Lucene50StoredFieldsFormat;
import org.apache.lucene.codecs.lucene70.Lucene70Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;


public class IndexEngine {
    public static String initIndex(Map<String, String> analyzerMap) throws IOException {
        addToIndex(analyzerMap);
        commitIndex();
        return null;
    }

    /**
     * Adds a batch without committing it. Full index builds may call this from
     * multiple workers and perform one commit after every worker has finished.
     */
    public static int addToIndex(Map<String, String> analyzerMap) throws IOException {
        if (analyzerMap == null || analyzerMap.isEmpty()) {
            return 0;
        }

        Collection<Document> documents = new ArrayList<>();
        for (Map.Entry<String, String> entry : analyzerMap.entrySet()) {
            String[] cut = StrUtil.cut(StrUtil.cleanBlank(
                    StrUtil.removeAllLineBreaks(entry.getValue())), 1800);
            for (String string : cut) {
                addDoc(entry.getKey(), string, documents);
            }
        }

        if (!documents.isEmpty()) {
            IndexSingletonClass.getIndexWriter().addDocuments(documents);
        }
        return analyzerMap.size();
    }

    public static void commitIndex() throws IOException {
        IndexSingletonClass.getIndexWriter().commit();
    }

    public static void resetIndex() throws IOException {
        IndexSingletonClass.reset();
    }

    private static void addDoc(String key, String string, Collection<Document> documents) {
        Document doc = new Document();
        doc.add(new StringField("content", string, Field.Store.YES));
        doc.add(new StringField("content_lower", string.toLowerCase(), Field.Store.YES));
        doc.add(new StringField("codePath", key, Field.Store.YES));
        doc.add(new StringField("title",
                StrUtil.removeSuffix(FileUtil.getName(key), ".class"), Field.Store.YES));
        documents.add(doc);
    }


    public static Result searchNormal(String keyword) throws IOException {
        if (StrUtil.isBlank(keyword)) {
            Result result = new Result();
            result.setTotal(0L);
            result.setData(new ArrayList<>());
            return result;
        }
        IndexReader reader = IndexSingletonClass.getReader();
        keyword = StrUtil.removeAllLineBreaks(keyword);
        //区分/忽略大小写查询
        Query query = new WildcardQuery(new Term(LuceneSearchForm.useCaseSensitive() ?
                "content" : "content_lower", LuceneSearchForm.useCaseSensitive() ?
                "*" + StrUtil.removeAllLineBreaks(StrUtil.cleanBlank(keyword)) + "*"
                : "*" + StrUtil.removeAllLineBreaks(StrUtil.cleanBlank(keyword.toLowerCase())) + "*"));
        return getResult(reader, IndexSingletonClass.getSearcher().search(query, 110));
    }

    public static Result searchRegex(String keyword) throws IOException {
        if (StrUtil.isBlank(keyword)) {
            Result result = new Result();
            result.setTotal(0L);
            result.setData(new ArrayList<>());
            return result;
        }
        IndexReader reader = IndexSingletonClass.getReader();
        keyword = StrUtil.removeAllLineBreaks(keyword);
        //区分/忽略大小写查询
        RegexpQuery query = new RegexpQuery(new Term(LuceneSearchForm.useCaseSensitive() ?
                "content" : "content_lower", LuceneSearchForm.useCaseSensitive() ?
                ".*" + Pattern.quote(StrUtil.removeAllLineBreaks(StrUtil.cleanBlank(keyword))) + ".*"
                : ".*" + Pattern.quote(StrUtil.removeAllLineBreaks(StrUtil.cleanBlank(keyword.toLowerCase()))) + ".*"));
        return getResult(reader, IndexSingletonClass.getSearcher().search(query, 110));
    }

    private static Result getResult(IndexReader reader, TopDocs search) throws IOException {

        ScoreDoc[] scoreDocs = search.scoreDocs;
        List<Map<String, Object>> arrayList = new ArrayList<>();
        for (ScoreDoc scoreDoc : scoreDocs) {
            int docID = scoreDoc.doc;
            Document doc = reader.document(docID);
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("path", doc.get("codePath"));
            map.put("content", doc.get("content"));
            map.put("title", doc.get("title"));
            arrayList.add(map);
        }
        Result result = new Result();
        result.setTotal(search.totalHits);
        result.setData(arrayList);
        return result;
    }

    public static class IndexSingletonClass {
        private static volatile IndexSearcher searcher = null;
        private static volatile IndexReader reader = null;
        private static volatile IndexWriter indexWriter = null;
        private static volatile Directory readerDirectory = null;
        private static volatile Directory writerDirectory = null;

        public static IndexSearcher getSearcher() throws IOException {
            if (searcher == null) {
                synchronized (IndexSingletonClass.class) {
                    if (searcher == null) {
                        IndexReader reader1 = getReader();
                        searcher = new IndexSearcher(reader1);
                    }
                }
            }
            return searcher;
        }

        public static IndexReader getReader() throws IOException {
            if (reader == null) {
                synchronized (IndexSingletonClass.class) {
                    if (reader == null) {
                        readerDirectory = FSDirectory.open(
                                Paths.get(IndexPluginsSupport.DocumentPath));
                        try {
                            reader = DirectoryReader.open(readerDirectory);
                        } catch (IOException e) {
                            readerDirectory.close();
                            readerDirectory = null;
                            throw e;
                        }
                    }
                }
            }
            return reader;
        }

        public static IndexWriter getIndexWriter() throws IOException {
            if (indexWriter == null) {
                synchronized (IndexSingletonClass.class) {

                    if (indexWriter == null) {
                        writerDirectory = FSDirectory.open(
                                Paths.get(IndexPluginsSupport.DocumentPath));
                        IndexWriterConfig conf = new IndexWriterConfig(new StandardAnalyzer());
                        conf.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                        //优化了索引文件编码，提高存储效率
                        conf.setCodec(new Lucene70Codec(Lucene50StoredFieldsFormat.Mode.BEST_COMPRESSION));
                        try {
                            indexWriter = new IndexWriter(writerDirectory, conf);
                        } catch (IOException e) {
                            writerDirectory.close();
                            writerDirectory = null;
                            throw e;
                        }
                    }
                }
            }
            return indexWriter;
        }

        private static synchronized void reset() throws IOException {
            IOException error = null;
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    error = e;
                }
            }
            if (readerDirectory != null) {
                try {
                    readerDirectory.close();
                } catch (IOException e) {
                    if (error == null) {
                        error = e;
                    }
                }
            }
            if (indexWriter != null) {
                try {
                    indexWriter.close();
                } catch (IOException e) {
                    if (error == null) {
                        error = e;
                    }
                }
            }
            if (writerDirectory != null) {
                try {
                    writerDirectory.close();
                } catch (IOException e) {
                    if (error == null) {
                        error = e;
                    }
                }
            }
            searcher = null;
            reader = null;
            indexWriter = null;
            readerDirectory = null;
            writerDirectory = null;
            if (error != null) {
                throw error;
            }
        }
    }
}
