package com.ash.springai.interview_platform.mapper;

import org.mapstruct.*;

import com.ash.springai.interview_platform.Entity.RagChatSessionEntity;
import com.ash.springai.interview_platform.Entity.RagChatDTO.SessionDTO;
import com.ash.springai.interview_platform.Entity.RagChatDTO.MessageDTO;
import com.ash.springai.interview_platform.Entity.RagChatMessageEntity;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.Entity.RagChatDTO.SessionListItemDTO;
import com.ash.springai.interview_platform.Entity.RagChatDTO.SessionDetailDTO;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseListItemDTO;

import java.util.List;
import java.util.Collection;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    uses = KnowledgeBaseMapper.class
)
public interface RagChatMapper {
    
    @Mapping(target = "knowledgeBaseIds", source = "session", qualifiedByName = "extractKnowledgeBaseIds")
    SessionDTO toSessionDTO(RagChatSessionEntity session);

    @Mapping(target = "type", source = "message", qualifiedByName = "getTypeString")
    MessageDTO toMessageDTO(RagChatMessageEntity message);

    List<MessageDTO> toMessageDTOList(List<RagChatMessageEntity> messages);

    @Named("extractKnowledgeBaseNames")
    default List<String> extractKnowledgeBaseNames(Collection<KnowledgeBaseEntity> knowledgeBases) {
        return knowledgeBases.stream()
            .map(KnowledgeBaseEntity::getName)
            .toList();
    }

    @Named("extractKnowledgeBaseIds")
    default List<Long> extractKnowledgeBaseIds(RagChatSessionEntity session) {
        return session.getKnowledgeBaseIds();
    }

    @Named("getTypeString")
    default String getTypeString(RagChatMessageEntity message) {
        return message.getTypeString();
    }

    @Mapping(target = "knowledgeBaseNames", source = "session.knowledgeBases", qualifiedByName = "extractKnowledgeBaseNames")
    @Mapping(target = "isPinned", source = "session", qualifiedByName = "getIsPinnedWithDefault")
    SessionListItemDTO toSessionListItemDTO(RagChatSessionEntity session);

    @Named("getIsPinnedWithDefault")
    default Boolean getIsPinnedWithDefault(RagChatSessionEntity session) {
        return session.getIsPinned() != null ? session.getIsPinned() : false;
    }

    default SessionDetailDTO toSessionDetailDTO(
            RagChatSessionEntity session, 
            List<RagChatMessageEntity> messages,
            List<KnowledgeBaseListItemDTO> knowledgeBases) {
        List<MessageDTO> messageDTOs = toMessageDTOList(messages);
        
        return new SessionDetailDTO(
            session.getId(),
            session.getTitle(),
            knowledgeBases,
            messageDTOs,
            session.getCreatedAt(),
            session.getUpdatedAt()
        );
    }
}
