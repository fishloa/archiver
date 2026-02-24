package com.icomb.archiver.repository;

import com.icomb.archiver.model.Attachment;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AttachmentRepository extends CrudRepository<Attachment, Long> {

  List<Attachment> findByRecordId(Long recordId);
}
