package com.insurance.explainer.analysis;

import com.insurance.explainer.document.*;
import com.insurance.explainer.persistence.LocalStateFiles;
import com.insurance.explainer.structure.Clause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DurableStoresTest {
    @TempDir Path directory;
    @Test void documents_and_running_analysis_restore_without_reparsing() {
        String path=directory.toString();
        var docs=new DocumentStore(path);
        var c=new Clause("c",null,"synthetic","article",null,null,1,false,"Synthetic fixture.");
        var ready=DocumentRecord.pending("doc",OffsetDateTime.now().plusHours(1)).withReady(1,List.of(),List.of(c));
        docs.save(ready);
        var store=new AnalysisStore(path);
        var record=AnalysisRecord.queued("a","doc",true,null,null,1,List.of(),List.of(c)).withStep(AnalysisStep.EXTRACTING);
        store.save(record);
        assertThat(new DocumentStore(path).get("doc")).isEqualTo(ready);
        var restored=new AnalysisStore(path).get("a");
        assertThat(restored.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(restored.errorCode()).isEqualTo("ANALYSIS_INTERRUPTED");
        assertThat(restored.clauses()).isEqualTo(record.clauses());
    }
    @Test void deleted_data_and_late_workers_cannot_resurrect_disk_records() {
        String path=directory.toString();var docs=new DocumentStore(path);
        var record=DocumentRecord.pending("doc",OffsetDateTime.now().plusHours(1));
        docs.save(record);docs.delete("doc");docs.save(record);
        assertThat(new DocumentStore(path).get("doc")).isNull();
        var analyses=new AnalysisStore(path);var a=AnalysisRecord.queued("a","doc",true,null,null,1,List.of(),List.of());
        analyses.save(a);analyses.delete("a");analyses.save(a);
        assertThat(new AnalysisStore(path).get("a")).isNull();
    }
    @Test void pending_upload_is_not_pretended_ready_after_crash() {
        String path=directory.toString();new DocumentStore(path).save(DocumentRecord.pending("doc",OffsetDateTime.now().plusHours(1)));
        assertThat(new DocumentStore(path).get("doc").status()).isEqualTo(DocumentStatus.REJECTED);
    }
    @Test void corrupted_checkpoint_is_rejected_without_exposing_its_body() throws Exception {
        var files=new LocalStateFiles(directory);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        files.write("id",mapper.readTree("{\"private\":\"SECRET\"}"));
        Path file;try(var all=Files.list(directory)){file=all.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow();}
        Files.writeString(file,Files.readString(file).replace("SECRET","CHANGED"));
        assertThatThrownBy(()->files.read("id")).isInstanceOf(LocalStateFiles.StorageFailure.class)
                .hasMessage("LOCAL_CHECKPOINT_STORAGE_ERROR");
    }
    @Test void one_writer_lease_is_released_for_a_later_server_instance() {
        String root=directory.toString();
        try(var first=new com.insurance.explainer.persistence.LocalStateLease(root)) {
            assertThatThrownBy(()->new com.insurance.explainer.persistence.LocalStateLease(root))
                    .isInstanceOf(LocalStateFiles.StorageFailure.class);
        }
        try(var restarted=new com.insurance.explainer.persistence.LocalStateLease(root)) {assertThat(restarted).isNotNull();}
    }
    @Test void emergency_memory_state_never_claims_running_or_successful() {
        var store=new AnalysisStore();var record=AnalysisRecord.queued("a","d",true,null,null,1,List.of(),List.of());
        store.save(record);store.markStorageFailure(record);
        assertThat(store.get("a").status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(store.get("a").errorCode()).isEqualTo("LOCAL_CHECKPOINT_STORAGE_ERROR");
        store.delete("a");store.markStorageFailure(record);assertThat(store.get("a")).isNull();
    }
}
