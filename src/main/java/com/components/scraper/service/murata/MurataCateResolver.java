package com.components.scraper.service.murata;

import com.components.scraper.config.MurataPathList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
@Getter
public class MurataCateResolver {

    private final MurataPathList pathList;

    public String cateFor(String categoryPath) {
        return pathList.getPathToCate().stream()
                .filter(m -> m.getPath()
                        .equalsIgnoreCase(categoryPath))
                .map(MurataPathList.PathMapping::getCate)
                .findFirst()
                .orElse(null);
    }
}
