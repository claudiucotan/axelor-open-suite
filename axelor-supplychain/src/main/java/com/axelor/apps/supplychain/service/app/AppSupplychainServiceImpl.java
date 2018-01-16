/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.supplychain.service.app;

import com.axelor.apps.base.db.AppSupplychain;
import com.axelor.apps.base.db.repo.AppSupplychainRepository;
import com.axelor.apps.base.service.app.AppBaseServiceImpl;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class AppSupplychainServiceImpl extends AppBaseServiceImpl implements AppSupplychainService {
	
	@Inject
	private AppSupplychainRepository appSupplychainRepo;
	
	@Override
	public AppSupplychain getAppSupplychain() {
		return appSupplychainRepo.all().fetchOne();
	}

}
