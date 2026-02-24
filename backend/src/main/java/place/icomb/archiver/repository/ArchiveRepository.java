package place.icomb.archiver.repository;

import place.icomb.archiver.model.Archive;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchiveRepository
    extends CrudRepository<Archive, Long>, PagingAndSortingRepository<Archive, Long> {}
