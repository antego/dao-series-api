/*
 * Copyright (C) 2015-2017 52°North Initiative for Geospatial Open Source
 * Software GmbH
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation.
 *
 * If the program is linked with libraries which are licensed under one of
 * the following licenses, the combination of the program with the linked
 * library is not considered a "derivative work" of the program:
 *
 *     - Apache License, version 2.0
 *     - Apache Software License, version 1.0
 *     - GNU Lesser General Public License, version 3
 *     - Mozilla Public License, versions 1.0, 1.1 and 2.0
 *     - Common Development and Distribution License (CDDL), version 1.0
 *
 * Therefore the distribution of the program linked with libraries licensed
 * under the aforementioned licenses, is permitted by the copyright holders
 * if the distribution is compliant with both the GNU General Public License
 * version 2 and the aforementioned licenses.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 */

package org.n52.series.db.da;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.Session;
import org.n52.io.DatasetFactoryException;
import org.n52.io.request.FilterResolver;
import org.n52.io.request.Parameters;
import org.n52.io.response.PlatformOutput;
import org.n52.io.response.PlatformType;
import org.n52.io.response.dataset.AbstractValue;
import org.n52.io.response.dataset.Data;
import org.n52.io.response.dataset.DatasetOutput;
import org.n52.series.db.DataAccessException;
import org.n52.series.db.beans.DatasetEntity;
import org.n52.series.db.beans.DescribableEntity;
import org.n52.series.db.beans.FeatureEntity;
import org.n52.series.db.beans.PlatformEntity;
import org.n52.series.db.beans.ProcedureEntity;
import org.n52.series.db.beans.parameter.Parameter;
import org.n52.series.db.dao.AbstractDao;
import org.n52.series.db.dao.DbQuery;
import org.n52.series.db.dao.FeatureDao;
import org.n52.series.db.dao.PlatformDao;
import org.n52.series.db.dao.SearchableDao;
import org.n52.series.spi.search.SearchResult;
import org.n52.web.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.vividsolutions.jts.geom.Geometry;

/**
 * TODO: JavaDoc
 *
 * @author <a href="mailto:h.bredel@52north.org">Henning Bredel</a>
 */
public class PlatformRepository extends ParameterRepository<PlatformEntity, PlatformOutput> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformRepository.class);

    private static final String FILTER_STATIONARY = "stationary";

    private static final String FILTER_MOBILE = "mobile";

    private static final String FILTER_INSITU = "insitu";

    private static final String FILTER_REMOTE = "remote";

    @Autowired
    private DatasetRepository<Data> seriesRepository;

    @Autowired
    private IDataRepositoryFactory factory;

    @Override
    protected PlatformOutput prepareEmptyParameterOutput(PlatformEntity entity) {
        return new PlatformOutput(entity.getPlatformType());
    }

    @Override
    protected SearchResult createEmptySearchResult(String id, String label, String baseUrl) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected String createHref(String hrefBase) {
        return urlHelper.getPlatformsHrefBaseUrl(hrefBase);
    }

    @Override
    protected AbstractDao<PlatformEntity> createDao(Session session) {
        return createPlatformDao(session);
    }

    private PlatformDao createPlatformDao(Session session) {
        return new PlatformDao(session);
    }

    private FeatureDao createFeatureDao(Session session) {
        return new FeatureDao(session);
    }

    @Override
    protected SearchableDao<PlatformEntity> createSearchableDao(Session session) {
        return createPlatformDao(session);
    }

    PlatformOutput createCondensed(DatasetEntity< ? > series, DbQuery query, Session session)
            throws DataAccessException {
        PlatformEntity entity = getEntity(getPlatformId(series), query, session);
        return createCondensed(entity, query, session);
    }

    PlatformOutput createCondensed(String id, DbQuery query, Session session) throws DataAccessException {
        PlatformEntity entity = getEntity(id, query, session);
        return createCondensed(entity, query, session);
    }

    @Override
    public boolean exists(String id, DbQuery query) throws DataAccessException {
        Session session = getSession();
        try {
            Long parsedId = parseId(PlatformType.extractId(id));
            AbstractDao< ? extends DescribableEntity> dao = PlatformType.isStationaryId(id)
                    ? createFeatureDao(session)
                    : createPlatformDao(session);
            return dao.hasInstance(parsedId, query);
        } finally {
            returnSession(session);
        }
    }

    @Override
    public PlatformOutput getInstance(String id, DbQuery query, Session session) throws DataAccessException {
        PlatformEntity entity = getEntity(id, query, session);
        return createExpanded(entity, query, session);
    }

    PlatformEntity getEntity(String id, DbQuery parameters, Session session) throws DataAccessException {
        if (PlatformType.isStationaryId(id)) {
            return getStation(id, parameters, session);
        } else {
            return getPlatform(id, parameters, session);
        }
    }

    @Override
    protected PlatformOutput createExpanded(PlatformEntity entity, DbQuery parameters, Session session)
            throws DataAccessException {
        PlatformOutput result = createCondensed(entity, parameters, session);
        DbQuery query = getDbQuery(parameters.getParameters()
                                             .extendWith(Parameters.PLATFORMS, result.getId())
                                             .removeAllOf(Parameters.FILTER_PLATFORM_TYPES));

        List<DatasetOutput> datasets = seriesRepository.getAllCondensed(query);
        result.setDatasets(datasets);

        Geometry geometry = entity.getGeometry();
        result.setGeometry(geometry == null
                ? getLastSamplingGeometry(datasets, query, session)
                : geometry);
        if (entity.hasParameters()) {
            String locale = parameters.getLocale();
            for (Parameter< ? > parameter : entity.getParameters()) {
                result.addParameter(parameter.toValueMap(locale));
            }
        }
        return result;
    }

    private Geometry getLastSamplingGeometry(List<DatasetOutput> datasets, DbQuery query, Session session)
            throws DataAccessException {
        AbstractValue< ? > currentLastValue = null;
        for (DatasetOutput dataset : datasets) {
            // XXX fix generics and inheritance of Data, AbstractValue, etc.
            // https://trello.com/c/dMVa0fg9/78-refactor-data-abstractvalue
            try {
                String id = dataset.getId();
                DataRepository dataRepository = factory.create(dataset.getValueType());
                DatasetEntity entity = seriesRepository.getInstanceEntity(id, query, session);
                AbstractValue< ? > valueToCheck = dataRepository.getLastValue(entity, session, query);
                currentLastValue = getLaterValue(currentLastValue, valueToCheck);
            } catch (DatasetFactoryException e) {
                LOGGER.error("Couldn't create data repository to determing last value of dataset '{}'",
                             dataset.getId());
            }
        }

        return currentLastValue != null && currentLastValue.isSetGeometry()
                ? currentLastValue.getGeometry()
                : null;
    }

    private AbstractValue< ? > getLaterValue(AbstractValue< ? > currentLastValue, AbstractValue< ? > valueToCheck) {
        if (currentLastValue == null) {
            return valueToCheck;
        }
        if (valueToCheck == null) {
            return currentLastValue;
        }
        return currentLastValue.getTimestamp() > valueToCheck.getTimestamp()
                ? currentLastValue
                : valueToCheck;
    }

    private PlatformEntity getStation(String id, DbQuery parameters, Session session) throws DataAccessException {
        String featureId = PlatformType.extractId(id);
        FeatureDao featureDao = createFeatureDao(session);
        FeatureEntity feature = featureDao.getInstance(Long.parseLong(featureId), parameters);
        if (feature == null) {
            throwNewResourceNotFoundException("Station", id);
        }
        return PlatformType.isInsitu(id)
                ? convertInsitu(feature)
                : convertRemote(feature);
    }

    private PlatformEntity getPlatform(String id, DbQuery parameters, Session session) throws DataAccessException {
        PlatformDao dao = createPlatformDao(session);
        String platformId = PlatformType.extractId(id);
        PlatformEntity result = dao.getInstance(Long.parseLong(platformId), parameters);
        if (result == null) {
            throwNewResourceNotFoundException("Platform", id);
        }
        return result;
    }

    @Override
    protected List<PlatformEntity> getAllInstances(DbQuery query, Session session) throws DataAccessException {
        List<PlatformEntity> platforms = new ArrayList<>();
        FilterResolver filterResolver = query.getFilterResolver();
        if (filterResolver.shallIncludeStationaryPlatformTypes()) {
            platforms.addAll(getAllStationary(query, session));
        }
        if (filterResolver.shallIncludeMobilePlatformTypes()) {
            platforms.addAll(getAllMobile(query, session));
        }
        return platforms;
    }

    private List<PlatformEntity> getAllStationary(DbQuery query, Session session) throws DataAccessException {
        List<PlatformEntity> platforms = new ArrayList<>();
        FilterResolver filterResolver = query.getFilterResolver();
        if (filterResolver.shallIncludeInsituPlatformTypes()) {
            platforms.addAll(getAllStationaryInsitu(query, session));
        }
        if (filterResolver.shallIncludeRemotePlatformTypes()) {
            platforms.addAll(getAllStationaryRemote(query, session));
        }
        return platforms;
    }

    private List<PlatformEntity> getAllStationaryInsitu(DbQuery parameters, Session session)
            throws DataAccessException {
        FeatureDao featureDao = createFeatureDao(session);
        DbQuery query = createPlatformFilter(parameters, FILTER_STATIONARY, FILTER_INSITU);
        return convertAllInsitu(featureDao.getAllInstances(query));
    }

    private List<PlatformEntity> getAllStationaryRemote(DbQuery parameters, Session session)
            throws DataAccessException {
        FeatureDao featureDao = createFeatureDao(session);
        DbQuery query = createPlatformFilter(parameters, FILTER_STATIONARY, FILTER_REMOTE);
        return convertAllRemote(featureDao.getAllInstances(query));
    }

    private List<PlatformEntity> getAllMobile(DbQuery query, Session session) throws DataAccessException {
        List<PlatformEntity> platforms = new ArrayList<>();
        FilterResolver filterResolver = query.getFilterResolver();
        if (filterResolver.shallIncludeInsituPlatformTypes()) {
            platforms.addAll(getAllMobileInsitu(query, session));
        }
        if (filterResolver.shallIncludeRemotePlatformTypes()) {
            platforms.addAll(getAllMobileRemote(query, session));
        }
        return platforms;
    }

    private List<PlatformEntity> getAllMobileInsitu(DbQuery parameters, Session session) throws DataAccessException {
        DbQuery query = createPlatformFilter(parameters, FILTER_MOBILE, FILTER_INSITU);
        PlatformDao dao = createPlatformDao(session);
        return dao.getAllInstances(query);
    }

    private List<PlatformEntity> getAllMobileRemote(DbQuery parameters, Session session) throws DataAccessException {
        DbQuery query = createPlatformFilter(parameters, FILTER_MOBILE, FILTER_REMOTE);
        PlatformDao dao = createPlatformDao(session);
        return dao.getAllInstances(query);
    }

    private DbQuery createPlatformFilter(DbQuery parameters, String... filterValues) {
        return getDbQuery(parameters.getParameters()
                                    .removeAllOf(Parameters.FILTER_PLATFORM_TYPES)
                                    .extendWith(Parameters.FILTER_PLATFORM_TYPES, filterValues));
    }

    private List<PlatformEntity> convertAllInsitu(List<FeatureEntity> entities) {
        List<PlatformEntity> converted = new ArrayList<>();
        for (FeatureEntity entity : entities) {
            converted.add(convertInsitu(entity));
        }
        return converted;
    }

    private List<PlatformEntity> convertAllRemote(List<FeatureEntity> entities) {
        List<PlatformEntity> converted = new ArrayList<>();
        for (FeatureEntity entity : entities) {
            converted.add(convertRemote(entity));
        }
        return converted;
    }

    private PlatformEntity convertInsitu(FeatureEntity entity) {
        PlatformEntity platform = convertToPlatform(entity);
        platform.setInsitu(true);
        return platform;
    }

    private PlatformEntity convertRemote(FeatureEntity entity) {
        PlatformEntity platform = convertToPlatform(entity);
        platform.setInsitu(false);
        return platform;
    }

    private PlatformEntity convertToPlatform(FeatureEntity entity) {
        PlatformEntity result = new PlatformEntity();
        result.setDomainId(entity.getDomainId());
        result.setPkid(entity.getPkid());
        result.setName(entity.getName());
        result.setParameters(entity.getParameters());
        result.setTranslations(entity.getTranslations());
        result.setDescription(entity.getDescription());
        result.setGeometry(entity.getGeometry());
        return result;
    }

    protected PlatformEntity getPlatformEntity(DatasetEntity< ? > series, DbQuery query, Session session)
            throws DataAccessException {
        // platform has to be handled dynamically (see #309)
        return getEntity(getPlatformId(series), query, session);
    }

    private String getPlatformId(DatasetEntity< ? > series) {
        ProcedureEntity procedure = series.getProcedure();
        PlatformType type = procedure.getPlatformType();
        DescribableEntity entity = type.isStationary()
                ? series.getFeature()
                : series.getProcedure();
        return type.createId(entity.getPkid());
    }

    private void throwNewResourceNotFoundException(String resource, String id) throws ResourceNotFoundException {
        throw new ResourceNotFoundException(resource + " with id '" + id + "' could not be found.");
    }

}
