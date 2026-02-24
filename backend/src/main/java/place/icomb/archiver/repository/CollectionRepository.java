package place.icomb.archiver.repository;

import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;
import place.icomb.archiver.model.Collection;

@Repository
public interface CollectionRepository
    extends CrudRepository<Collection, Long>, PagingAndSortingRepository<Collection, Long> {

  List<Collection> findByArchiveId(Long archiveId);
}
