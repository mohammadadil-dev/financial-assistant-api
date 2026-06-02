# Financial Chatbot

## Overview
Financial Chatbot is a Spring Boot application built using Spring AI, Ollama, and Pinecone.  
It uses Retrieval-Augmented Generation (RAG) to provide context-aware responses for financial queries.

The chatbot supports both FAQ-based responses and structured financial flows like loan eligibility and EMI calculation.

---

## Tech Stack

- Java 21  
- Spring Boot 3.5.3  
- Spring AI  
- Ollama (LLM - llama3)  
- Pinecone (Vector Database)  
- Gradle  

---

## Features

- Chat API for financial queries
   
- RAG-based response generation
    
- English and Arabic language support
    
- Loan eligibility flow
    
- EMI calculation
    
- Loan status tracking using National ID
    
- Quick reply options support
    
- Knowledge base integration from local resources  

---

## Project Structure
src/main/java/com/beginner_techies/chatbotapp

├── config
├── controller
├── dto
├── enums
├── record
├── service
├── util
└── ChatbotappApplication.java

src/main/resources
├── application.properties
└── knowledge

---

## Configuration

### Environment Variables

PINECONE_API=your-pinecone-api-key

INDEX_NAME=your-index-name


### application.properties
spring.application.name=chatbotapp

spring.ai.vectorstore.pinecone.apiKey=${PINECONE_API}

spring.ai.vectorstore.pinecone.index-name=${INDEX_NAME}

spring.ai.ollama.base-url=http://localhost:11434

spring.ai.ollama.model=llama3

spring.ai.ollama.init.embedding.additional-models=nomic-embed-text


---

## Setup

### 1. Start Ollama
ollama serve


### 2. Pull Models
ollama pull llama3
ollama pull nomic-embed-text

### 3. Run Application


./gradlew bootRun


---

## API

### POST /api/chat

#### Request (English)
{
"content": "I want to check loan eligibility",
"sender": "user1",
"lang": "en"
}

#### Request (Arabic)
{
"content": "أريد التحقق من الأهلية",
"sender": "user1",
"lang": "ar"
}

#### Response
{
"message": "Chatbot response",
"options": []
}


---

## Flow

1. User sends request to `/api/chat`  
2. Application processes language and intent  
3. For general queries → RAG flow is triggered  
4. Relevant data is fetched from Pinecone  
5. Context + query is sent to Ollama  
6. Response is returned to the user  

---

## Build
./gradlew build

---

## Test
./gradlew test
