package place.icomb.archiver.repository;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import place.icomb.archiver.model.AppUserEmail;

@Repository
public interface AppUserEmailRepository extends CrudRepository<AppUserEmail, Long> {

  @Query("SELECT * FROM app_user_email WHERE user_id = :userId")
  List<AppUserEmail> findByUserId(@Param("userId") Long userId);
}
