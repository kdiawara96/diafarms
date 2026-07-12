
package com.diafarms.ml.services;

import java.util.List;
import com.diafarms.ml.DTO.RaceDTO;
import com.diafarms.ml.models.Race;
import com.diafarms.ml.others.PaginatedResponse;

public interface RaceServices {
	RaceDTO create(Race race);
	RaceDTO update(String uniqueId, Race race);
	String deleteOrRecover(String uniqueIdRace);
	List<RaceDTO> findAll();
	List<RaceDTO> search(String search);

	List<RaceDTO> select();

	PaginatedResponse<RaceDTO> listPaginated(int page, int size, String search);

}
