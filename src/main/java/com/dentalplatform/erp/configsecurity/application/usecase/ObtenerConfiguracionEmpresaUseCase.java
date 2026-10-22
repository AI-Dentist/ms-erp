package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.domain.model.EmpresaConfig;
import com.dentalplatform.erp.configsecurity.domain.repository.EmpresaConfigRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObtenerConfiguracionEmpresaUseCase {

    private final EmpresaConfigRepository repository;

    public ObtenerConfiguracionEmpresaUseCase(EmpresaConfigRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public EmpresaConfig ejecutar() {
        return repository.obtener()
                .orElseThrow(() -> new ResourceNotFoundException("La configuración de la empresa no fue registrada"));
    }
}
