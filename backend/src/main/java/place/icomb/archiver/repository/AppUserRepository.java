package place.icomb.archiver.repository;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import place.icomb.archiver.model.AppUser;

@Repository
public interface AppUserRepository extends CrudRepository<AppUser, Long> {

  @Query(
      "SELECT u.* FROM app_user u JOIN app_user_email e ON e.user_id = u.id WHERE e.email = :email")
  Optional<AppUser> findByEmail(@Param("email") String email);
}
