package com.slotwise.booking.service;

import com.slotwise.booking.data.Resource;
import com.slotwise.booking.model.ResourceDto;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class ResourceToResourceDtoConverter implements Converter<Resource, ResourceDto> {

    @Override
    public ResourceDto convert(Resource source) {
        return ResourceDto.builder()
                .id(source.getId())
                .name(source.getName())
                .description(source.getDescription())
                .active(source.isActive())
                .build();
    }
}
