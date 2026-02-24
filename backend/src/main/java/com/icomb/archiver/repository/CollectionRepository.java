package com.icomb.archiver.repository;

import com.icomb.archiver.model.Collection;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CollectionRepository
    extends CrudRepository<Collection, Long>, PagingAndSortingRepository<Collection, Long> {

  List<Collection> findByArchiveId(Long archiveId);
}
