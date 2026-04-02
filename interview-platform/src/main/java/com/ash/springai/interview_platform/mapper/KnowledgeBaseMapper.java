package com.ash.springai.interview_platform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.ash.springai.interview_platform.Entity.KnowledgeBaseListItemDTO;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;

import java.util.List;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface KnowledgeBaseMapper {

    KnowledgeBaseListItemDTO toListItemDTO(KnowledgeBaseEntity entity);

    List<KnowledgeBaseListItemDTO> toListItemDTOList(List<KnowledgeBaseEntity> entities);
}
