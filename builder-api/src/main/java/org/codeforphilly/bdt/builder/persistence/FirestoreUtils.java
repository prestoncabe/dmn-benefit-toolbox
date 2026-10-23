package org.codeforphilly.bdt.builder.persistence;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;
import io.quarkus.logging.Log;
import org.codeforphilly.bdt.builder.model.domain.Screener;

import java.util.*;
import java.util.concurrent.ExecutionException;

public class FirestoreUtils {

    private static final Firestore db = FirestoreClient.getFirestore();

    public static List<Map<String, Object>> getAllDocsInCollection(String collection){
        try {
            ApiFuture<QuerySnapshot> query = db.collection(collection)
                    .get();
            List<QueryDocumentSnapshot> documents;
            documents = query.get().getDocuments();

            return documents.stream()
                    .map(doc -> {
                        Map<String, Object> data = doc.getData();
                        data.put("id", doc.getId());
                        return data;
                    })
                    .toList();

        }catch(Exception e){
            Log.error("Error fetching documents from firestore: ", e);
            return new ArrayList<>();
        }
    }

    public static List<Map<String, Object>> getFirestoreDocsByField(String collection, String field, String value) {
        System.out.println("Fetching documents from collection: " + collection + " where " + field + " = " + value);
        System.out.println("Using Firestore instance: " + db.listCollections());
        try {
            ApiFuture<QuerySnapshot> query = db.collection(collection)
                    .whereEqualTo(field, value)
                    .get();
            List<QueryDocumentSnapshot> documents;
            documents = query.get().getDocuments();

            return documents.stream()
                    .map(doc -> {
                        Map<String, Object> data = doc.getData();
                        data.put("id", doc.getId());
                        return data;
                    })
                    .toList();
        }catch(Exception e){
            Log.error("Error fetching documents from firestore: ", e);
            return new ArrayList<>();
        }
    }

    /* Read failures are propagated so callers can tell an empty result apart from an unavailable read. */
    public static List<Map<String, Object>> getFirestoreDocsByFields(String collection, Map<String, String> fieldValues) throws Exception {
        System.out.println("Fetching documents from collection: " + collection + " with field values: " + fieldValues);
        Query query = db.collection(collection);
        for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
            // Add a whereEqualTo clause for each field-value pair
            query = query.whereEqualTo(entry.getKey(), entry.getValue());
        }
        ApiFuture<QuerySnapshot> querySnapshot = query.get();
        List<QueryDocumentSnapshot> documents;
        documents = querySnapshot.get().getDocuments();

        return documents.stream()
                .map(doc -> {
                    Map<String, Object> data = doc.getData();
                    data.put("id", doc.getId());
                    return data;
                })
                .toList();
    }

    public static List<Map<String, Object>> getFirestoreDocsByField(String collection, String field, boolean value) {
        try {
            ApiFuture<QuerySnapshot> query = db.collection(collection)
                    .whereEqualTo(field, value)
                    .get();
            List<QueryDocumentSnapshot> documents;
            documents = query.get().getDocuments();

            return documents.stream()
                    .map(doc -> {
                        Map<String, Object> data = doc.getData();
                        data.put("id", doc.getId());
                        return data;
                    })
                    .toList();

        }catch(Exception e){
            Log.error("Error fetching documents from firestore: ", e);
            return new ArrayList<>();
        }
    }

    public static List<Map<String, Object>> getFirestoreDocsByIds(String collection, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // Create document references for all IDs
            List<DocumentReference> docRefs = ids.stream()
                    .map(id -> db.collection(collection).document(id))
                    .toList();

            // Batch get all documents
            List<DocumentSnapshot> snapshots = db.getAll(docRefs.toArray(new DocumentReference[0])).get();


            // Might be an issue with the colon in the IDs
            // Add after getting snapshots
            for (int i = 0; i < snapshots.size(); i++) {
                DocumentSnapshot doc = snapshots.get(i);
                Log.info("Document ID: " + ids.get(i) + ", Exists: " + doc.exists() + ", Reference: " + doc.getReference().getPath());
            }
            // Process results, filtering out non-existent documents
            List<Map<String, Object>> results = new ArrayList<>();
            for (DocumentSnapshot doc : snapshots) {
                if (doc.exists()) {
                    Map<String, Object> data = doc.getData();
                    data.put("id", doc.getId());
                    results.add(data);
                }
            }

            return results;
        } catch (Exception e) {
            Log.error("Error fetching documents from firestore: ", e);
            return new ArrayList<>();
        }
    }

    public static Optional<Map<String, Object>> getFirestoreDocById(String collection, String id) {
        try {
            DocumentSnapshot doc = db.collection(collection)
                    .document(id)
                    .get().get();


            if (!doc.exists()) {
                return Optional.empty();
            }

            Map<String, Object> data = doc.getData();
            data.put("id", doc.getId());

            return Optional.of(data);

        }catch(Exception e){
            Log.error("Error fetching document from firestore: ", e);
            return Optional.empty();
        }
    }

    public static String persistDocument(String collectionName, Map<String, Object> data) throws Exception {
        try {
            DocumentReference documentRef = db.collection(collectionName)
                    .add(data)
                    .get();
            return documentRef.getId();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // preserve interrupt status
            throw new Exception("Thread interrupted while saving to Firestore", e);
        } catch (ExecutionException e) {
            throw new Exception("Failed to write document to Firestore", e);
        }
    }


    public static String persistDocumentWithId(String collectionPath, String documentId, Map<String, Object> data) throws Exception {
        try {
            DocumentReference documentRef = db.collection(collectionPath).document(documentId);
            documentRef.create(data).get();
            return documentRef.getId();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.error(e);
            throw new Exception("Thread interrupted while saving to Firestore", e);
        } catch (ExecutionException e) {
            Log.error(e);
            if (e.getCause() instanceof ApiException apiException
                    && apiException.getStatusCode().getCode() == StatusCode.Code.ALREADY_EXISTS) {
                throw new DocumentAlreadyExistsException(documentId, e);
            }
            throw new Exception("Failed to write document to Firestore", e);
        }
    }


    /* Writes a document under a known ID, replacing any document already there. */
    public static void replaceDocument(String collectionPath, String documentId, Map<String, Object> data) throws Exception {
        try {
            db.collection(collectionPath).document(documentId).set(data).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Thread interrupted while saving to Firestore", e);
        } catch (ExecutionException e) {
            throw new Exception("Failed to write document to Firestore", e);
        }
    }


    /* Reads a document inside a transaction, so the transaction fails if it changes before commit. */
    public interface TransactionReader {
        Optional<Map<String, Object>> get(String collection, String id) throws Exception;
    }

    public interface ReplaceableCheck {
        boolean isReplaceable(Map<String, Object> existing, TransactionReader reader) throws Exception;
    }

    /* Atomically creates the document, or replaces an existing one only when isReplaceable says so.
       Throws DocumentAlreadyExistsException when an existing document is kept. */
    public static void createDocumentUnlessHeld(String collection, String documentId, Map<String, Object> data,
                                                ReplaceableCheck replaceableCheck) throws Exception {
        DocumentReference documentRef = db.collection(collection).document(documentId);
        boolean created;
        try {
            created = db.runTransaction(transaction -> {
                TransactionReader reader = (readCollection, readId) -> {
                    DocumentSnapshot doc = transaction.get(db.collection(readCollection).document(readId)).get();
                    return doc.exists() ? Optional.of(doc.getData()) : Optional.empty();
                };
                DocumentSnapshot existing = transaction.get(documentRef).get();
                if (existing.exists() && !replaceableCheck.isReplaceable(existing.getData(), reader)) {
                    return false;
                }
                transaction.set(documentRef, data);
                return true;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Thread interrupted while saving to Firestore", e);
        } catch (ExecutionException e) {
            Log.error(e);
            throw new Exception("Failed to write document to Firestore", e);
        }
        if (!created) {
            throw new DocumentAlreadyExistsException(documentId, null);
        }
    }

    /** Serializes name changes per owner and includes existing screeners without a name index. */
    public static void saveScreenerWithUniqueName(String collection, String id, Map<String, Object> data,
                                                  boolean create) throws Exception {
        String owner = (String) data.get("ownerId");
        String name = (String) data.get("screenerName");
        DocumentReference ownerLock = db.collection("screenerNameLocks").document(owner);
        DocumentReference document = db.collection(collection).document(id);
        try {
            db.runTransaction(transaction -> {
                transaction.get(ownerLock).get();
                QuerySnapshot screeners = transaction.get(db.collection(collection)
                        .whereEqualTo("ownerId", owner)).get();
                for (QueryDocumentSnapshot existing : screeners.getDocuments()) {
                    if (!id.equals(existing.getId()) && Screener.normalizeName(name)
                            .equals(Screener.normalizeName(existing.getString("screenerName")))) {
                        throw new DuplicateScreenerNameException();
                    }
                }
                if (create) transaction.create(document, data);
                else transaction.set(document, data, SetOptions.merge());
                transaction.set(ownerLock, Map.of("lastChange", UUID.randomUUID().toString()));
                return null;
            }).get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof DuplicateScreenerNameException duplicate) throw duplicate;
                cause = cause.getCause();
            }
            throw failure;
        }
    }

    public static void updateDocument(String collectionName, Map<String, Object> data, String docId) throws Exception {
        try {
            WriteResult result = db.collection(collectionName)
                    .document(docId)
                    .set(data, SetOptions.merge())
                    .get();
            Log.info("Document " + docId + " updated at " + result.getUpdateTime());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // preserve interrupt status
            throw new Exception("Thread interrupted while saving to Firestore", e);
        } catch (ExecutionException e) {
            throw new Exception("Failed to write document to Firestore", e);
        }
    }

    public static void addObjectToArrayField(String collectionName,
                                             String docId,
                                             String field,
                                             Map<String, Object> data) throws Exception{
        try {
            DocumentReference projectRef = db.collection(collectionName).document(docId);

            ApiFuture<WriteResult> future = projectRef.update(
                    field, FieldValue.arrayUnion(data)
            );

            // Wait for the update to complete
            future.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.error(e);
            throw new Exception("Thread interrupted while updating array field", e);
        } catch (Exception e) {
            Log.error(e);
            throw new Exception("Failed to update array field", e);
        }
    }

    public static void deleteDocument(String collectionName, String docId) throws Exception {
        try{
            WriteResult result = db.collection(collectionName).document(docId).delete().get();
            Log.info("Document " + docId + " deleted at " + result.getUpdateTime());
        } catch (Exception e){
            Log.error("Failed to delete document from firestore");
            throw new Exception(e);
        }
    }

    public static void deleteAllDocuments(String collectionName) throws Exception {
        try{
            db.collection(collectionName).listDocuments().forEach(
                documentRef -> {
                    try {
                        WriteResult result = documentRef.delete().get();
                        Log.info("Document " + documentRef.getId() + " deleted at " + result.getUpdateTime());
                    } catch (Exception e) {
                        Log.error("Failed to delete document: " + documentRef.getId(), e);
                    }
                }
            );
        } catch (Exception e){
            Log.error("Failed to delete document from firestore");
            throw new Exception(e);
        }
    }

    public static void addObjectToListFieldOfDocument(String collection, String docId, String field, Object object) throws Exception{
        try{
            DocumentReference docRef = db.collection(collection).document(docId);
            docRef.update(field, FieldValue.arrayUnion(object));
        } catch (Exception e){
            Log.error("Failed to add object to list field of collection.");
            throw new Exception(e);
        }
    }

    public static void removeObjectFromListFieldOfDocument(String collection, String docId, String field, Object object) throws Exception {
        try {
            DocumentReference docRef = db.collection(collection).document(docId);
            docRef.update(field, FieldValue.arrayRemove(object));
        } catch (Exception e) {
            Log.error("Failed to remove object from list field of collection.");
            throw new Exception(e);
        }
    }
}
