package dev.sunbirdrc.claim.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sunbirdrc.claim.dto.ClaimWithNotesDTO;
import dev.sunbirdrc.claim.entity.Claim;
import dev.sunbirdrc.claim.entity.ClaimNote;
import dev.sunbirdrc.claim.exception.ClaimAlreadyProcessedException;
import dev.sunbirdrc.claim.exception.ResourceNotFoundException;
import dev.sunbirdrc.claim.exception.UnAuthorizedException;
import dev.sunbirdrc.claim.model.ClaimStatus;
import dev.sunbirdrc.claim.repository.ClaimNoteRepository;
import dev.sunbirdrc.claim.repository.ClaimRepository;
import dev.sunbirdrc.registry.middleware.util.EntityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static dev.sunbirdrc.claim.contants.AttributeNames.ATTESTOR_INFO;
import static dev.sunbirdrc.claim.contants.AttributeNames.NOTES;
import static dev.sunbirdrc.claim.contants.ErrorMessages.*;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final ClaimNoteRepository claimNoteRepository;
    private final SunbirdRCClient sunbirdRCClient;
    private final ClaimsAuthorizer claimsAuthorizer;
    private static final Logger logger = LoggerFactory.getLogger(ClaimService.class);

    @Autowired
    public ClaimService(ClaimRepository claimRepository, ClaimNoteRepository claimNoteRepository, SunbirdRCClient sunbirdRCClient, ClaimsAuthorizer claimsAuthorizer) {
        this.claimRepository = claimRepository;
        this.claimNoteRepository = claimNoteRepository;
        this.sunbirdRCClient = sunbirdRCClient;
        this.claimsAuthorizer = claimsAuthorizer;
    }

    public Claim save(Claim claim) {
        return claimRepository.save(claim);
    }

    public Optional<Claim> findById(String id) {
        return claimRepository.findById(id);
    }

    public List<Claim> findAll() {
        return claimRepository.findAll();
    }

    public List<Claim> findClaimsForAttestor(String entity, JsonNode attestorNode) {
        List<Claim> claims = claimRepository.findByAttestorEntity(entity);
        logger.info("Found {} claims to process", claims.size());
        return claims.stream()
                .filter(claim -> claimsAuthorizer.isAuthorizedAttestor(claim, attestorNode))
                .collect(Collectors.toList());
    }

    public Claim attestClaim(String claimId, JsonNode requestBody) {
        Claim claim = findById(claimId).orElseThrow(() -> new ResourceNotFoundException(CLAIM_NOT_FOUND));
        logger.info("Processing claim {}", claim.toString());
        if (claim.isClosed()) {
            throw new ClaimAlreadyProcessedException(CLAIM_IS_ALREADY_PROCESSED);
        }
        JsonNode attestorNode = requestBody.get(ATTESTOR_INFO);
        if (!claimsAuthorizer.isAuthorizedAttestor(claim, attestorNode)) {
            throw new UnAuthorizedException(USER_NOT_AUTHORIZED);
        }
        return updateClaim(requestBody, claim);
    }

    private Claim updateClaim(JsonNode requestBody, Claim claim) {
        JsonNode attestorNode = requestBody.get(ATTESTOR_INFO);
        if(requestBody.has(NOTES)) {
            addNotes(requestBody.get(NOTES).asText(), claim, EntityUtil.getFullNameOfTheEntity(attestorNode));
        }
        claim.setAttestedOn(new Date());
        claim.setStatus(ClaimStatus.CLOSED.name());
        return claimRepository.save(claim);
    }

    public void addNotes(String notes, Claim claim, String addedBy) {
        ClaimNote claimNote = new ClaimNote();
        claimNote.setNotes(notes);
        claimNote.setPropertyURI(claim.getPropertyURI());
        claimNote.setEntityId(claim.getEntityId());
        claimNote.setAddedBy(addedBy);
        claimNote.setClaimId(claim.getId());
        claimNoteRepository.save(claimNote);
    }

    public List<ClaimNote> getClaimWithNotes(Claim claim) {
        return claimNoteRepository.findByEntityIdAndClaimId(claim.getEntityId(), claim.getId());
    }

    public ClaimWithNotesDTO generateNotesForTheClaim(Claim claim) {
        List<ClaimNote> notes = getClaimWithNotes(claim);
        ClaimWithNotesDTO claimWithNotesDTO = new ClaimWithNotesDTO();
        claimWithNotesDTO.setNotes(notes);
        claimWithNotesDTO.setClaim(claim);
        return claimWithNotesDTO;
    }
}
