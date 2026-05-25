package com.diafarms.ml.ServiceImpl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.RaceDTO;
import com.diafarms.ml.models.Race;
import com.diafarms.ml.services.RaceServices;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class Raceimpl  implements RaceServices {
    
    @Override
    public RaceDTO create(Race race) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'create'");
    }

    @Override
    public RaceDTO update(Race race) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'update'");
    }

    @Override
    public String deleteOrRecover(String uniqueIdRace) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteOrRecover'");
    }

    @Override
    public List<RaceDTO> findAll() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findAll'");
    }

    @Override
    public List<RaceDTO> search(String search) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'search'");
    }
    
}
