package com.interview.agent.jd;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.exception.BaseException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JdService {
    private final JdMapper jdMapper;

    public JdService(JdMapper jdMapper) {
        this.jdMapper = jdMapper;
    }

    public Jd create(JdCreateDTO dto) {
        Jd jd = new Jd();
        jd.setUserId(BaseContext.getCurrentId());
        jd.setTitle(dto.getTitle());
        jd.setRawText(dto.getRawText());
        jd.setSourceUrl(dto.getSourceUrl());
        jdMapper.insert(jd);
        return jd;
    }

    public List<Jd> list() {
        return jdMapper.findByUserId(BaseContext.getCurrentId());
    }

    public Jd getById(Long id) {
        Jd jd = jdMapper.findById(id);
        if (jd == null) throw new BaseException("JD 不存在");
        if (!jd.getUserId().equals(BaseContext.getCurrentId())) {
            throw new BaseException("无权访问该 JD");
        }
        return jd;
    }

    public void delete(Long id) {
        int affected = jdMapper.deleteByIdAndUserId(id, BaseContext.getCurrentId());
        if (affected == 0) throw new BaseException("JD 不存在或无权删除");
    }
}
