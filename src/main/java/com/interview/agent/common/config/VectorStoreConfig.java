package com.interview.agent.common.config;

import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置 — 使用 Elasticsearch 作为向量数据库（知识库 RAG）。
 * DashScope qwen3.7-text-embedding 输出 1024 维向量（默认）。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(RestClient restClient, EmbeddingModel embeddingModel) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setDimensions(1024);

        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(true)
                .build();
    }
}
