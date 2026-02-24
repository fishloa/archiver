package place.icomb.archiver.repository;

import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import place.icomb.archiver.model.Attachment;

@Repository
public interface AttachmentRepository extends CrudRepository<Attachment, Long> {

  List<Attachment> findByRecordId(Long recordId);
}
