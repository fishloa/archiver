package com.icomb.archiver.repository;

import com.icomb.archiver.model.Archive;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchiveRepository
    extends CrudRepository<Archive, Long>, PagingAndSortingRepository<Archive, Long> {}
