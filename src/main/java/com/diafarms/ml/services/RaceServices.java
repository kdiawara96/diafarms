
package com.diafarms.ml.services;

import java.util.List;
import com.diafarms.ml.DTO.RaceDTO;
import com.diafarms.ml.models.Race;

public interface RaceServices {
	RaceDTO create(Race race);
	RaceDTO update(String uniqueId, Race race);
	String deleteOrRecover(String uniqueIdRace);
	List<RaceDTO> findAll();
	List<RaceDTO> search(String search);
}
