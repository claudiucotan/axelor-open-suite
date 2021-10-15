package com.axelor.apps.account.service.moveline;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AnalyticAccount;
import com.axelor.apps.account.db.AnalyticMoveLine;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.AnalyticMoveLineRepository;
import com.axelor.apps.account.exception.IExceptionMessage;
import com.axelor.apps.account.service.AnalyticMoveLineService;
import com.axelor.apps.account.service.FiscalPositionAccountService;
import com.axelor.apps.account.service.TaxAccountService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.service.CurrencyService;
import com.axelor.apps.base.service.config.CompanyConfigService;
import com.axelor.apps.tool.StringTool;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoveLineCreateServiceImpl implements MoveLineCreateService {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected CompanyConfigService companyConfigService;
  protected CurrencyService currencyService;
  protected FiscalPositionAccountService fiscalPositionAccountService;
  protected AnalyticMoveLineRepository analyticMoveLineRepository;
  protected AnalyticMoveLineService analyticMoveLineService;
  protected TaxAccountService taxAccountService;
  protected InvoiceService invoiceService;
  protected MoveLineToolService moveLineToolService;
  protected MoveLineComputeAnalyticService moveLineComputeAnalyticService;
  protected MoveLineConsolidateService moveLineConsolidateService;

  @Inject
  public MoveLineCreateServiceImpl(
      CompanyConfigService companyConfigService,
      CurrencyService currencyService,
      FiscalPositionAccountService fiscalPositionAccountService,
      AnalyticMoveLineRepository analyticMoveLineRepository,
      AnalyticMoveLineService analyticMoveLineService,
      TaxAccountService taxAccountService,
      InvoiceService invoiceService,
      MoveLineToolService moveLineToolService,
      MoveLineComputeAnalyticService moveLineComputeAnalyticService,
      MoveLineConsolidateService moveLineConsolidateService) {
    this.companyConfigService = companyConfigService;
    this.currencyService = currencyService;
    this.fiscalPositionAccountService = fiscalPositionAccountService;
    this.analyticMoveLineRepository = analyticMoveLineRepository;
    this.analyticMoveLineService = analyticMoveLineService;
    this.taxAccountService = taxAccountService;
    this.invoiceService = invoiceService;
    this.moveLineToolService = moveLineToolService;
    this.moveLineComputeAnalyticService = moveLineComputeAnalyticService;
    this.moveLineConsolidateService = moveLineConsolidateService;
  }

  /**
   * Creating accounting move line method using move currency
   *
   * @param move
   * @param partner
   * @param account
   * @param amountInSpecificMoveCurrency
   * @param isDebit <code>true = debit</code>, <code>false = credit</code>
   * @param date
   * @param dueDate
   * @param counter
   * @param origin
   * @return
   * @throws AxelorException
   */
  @Override
  public MoveLine createMoveLine(
      Move move,
      Partner partner,
      Account account,
      BigDecimal amountInSpecificMoveCurrency,
      boolean isDebit,
      LocalDate date,
      LocalDate dueDate,
      int counter,
      String origin,
      String description)
      throws AxelorException {

    log.debug(
        "Creating accounting move line (Account : {}, Amount in specific move currency : {}, debit ? : {}, date : {}, counter : {}, reference : {}",
        new Object[] {
          account.getName(), amountInSpecificMoveCurrency, isDebit, date, counter, origin
        });

    Currency currency = move.getCurrency();
    Currency companyCurrency = companyConfigService.getCompanyCurrency(move.getCompany());

    BigDecimal currencyRate =
        currencyService.getCurrencyConversionRate(currency, companyCurrency, date);

    BigDecimal amountConvertedInCompanyCurrency =
        currencyService.getAmountCurrencyConvertedUsingExchangeRate(
            amountInSpecificMoveCurrency, currencyRate);

    return this.createMoveLine(
        move,
        partner,
        account,
        amountInSpecificMoveCurrency,
        amountConvertedInCompanyCurrency,
        currencyRate,
        isDebit,
        date,
        dueDate,
        date,
        counter,
        origin,
        description);
  }

  /**
   * Creating accounting move line method using all currency information (amount in specific move
   * currency, amount in company currency, currency rate)
   *
   * @param move
   * @param partner
   * @param account
   * @param amountInSpecificMoveCurrency
   * @param amountInCompanyCurrency
   * @param currencyRate
   * @param isDebit
   * @param date
   * @param dueDate
   * @param counter
   * @param origin
   * @return
   * @throws AxelorException
   */
  @Override
  public MoveLine createMoveLine(
      Move move,
      Partner partner,
      Account account,
      BigDecimal amountInSpecificMoveCurrency,
      BigDecimal amountInCompanyCurrency,
      BigDecimal currencyRate,
      boolean isDebit,
      LocalDate date,
      LocalDate dueDate,
      LocalDate originDate,
      int counter,
      String origin,
      String description)
      throws AxelorException {

    amountInSpecificMoveCurrency = amountInSpecificMoveCurrency.abs();

    log.debug(
        "Creating accounting move line (Account : {}, Amount in specific move currency : {}, debit ? : {}, date : {}, counter : {}, reference : {}",
        new Object[] {
          account.getName(), amountInSpecificMoveCurrency, isDebit, date, counter, origin
        });

    if (partner != null) {
      account = fiscalPositionAccountService.getAccount(partner.getFiscalPosition(), account);
    }

    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;

    if (amountInCompanyCurrency.compareTo(BigDecimal.ZERO) == -1) {
      isDebit = !isDebit;
      amountInCompanyCurrency = amountInCompanyCurrency.negate();
    }

    if (isDebit) {
      debit = amountInCompanyCurrency;
    } else {
      credit = amountInCompanyCurrency;
    }

    if (currencyRate == null) {
      if (amountInSpecificMoveCurrency.compareTo(BigDecimal.ZERO) == 0) {
        currencyRate = BigDecimal.ONE;
      } else {
        currencyRate =
            amountInCompanyCurrency.divide(amountInSpecificMoveCurrency, 5, RoundingMode.HALF_UP);
      }
    }

    if (originDate == null) {
      originDate = date;
    }

    return new MoveLine(
        move,
        partner,
        account,
        date,
        dueDate,
        counter,
        debit,
        credit,
        StringTool.cutTooLongString(
            moveLineToolService.determineDescriptionMoveLine(
                move.getJournal(), origin, description)),
        origin,
        currencyRate.setScale(5, RoundingMode.HALF_UP),
        amountInSpecificMoveCurrency,
        originDate);
  }

  /**
   * Créer une ligne d'écriture comptable
   *
   * @param move
   * @param partner
   * @param account
   * @param amount
   * @param isDebit <code>true = débit</code>, <code>false = crédit</code>
   * @param date
   * @param ref
   * @param origin
   * @return
   * @throws AxelorException
   */
  @Override
  public MoveLine createMoveLine(
      Move move,
      Partner partner,
      Account account,
      BigDecimal amount,
      boolean isDebit,
      LocalDate date,
      int ref,
      String origin,
      String description)
      throws AxelorException {

    return this.createMoveLine(
        move, partner, account, amount, isDebit, date, date, ref, origin, description);
  }

  /**
   * Créer les lignes d'écritures comptables d'une facture.
   *
   * @param invoice
   * @param move
   * @param consolidate
   * @return
   */
  @Override
  public List<MoveLine> createMoveLines(
      Invoice invoice,
      Move move,
      Company company,
      Partner partner,
      Account partnerAccount,
      boolean consolidate,
      boolean isPurchase,
      boolean isDebitCustomer)
      throws AxelorException {

    log.debug(
        "Création des lignes d'écriture comptable de la facture/l'avoir {}",
        invoice.getInvoiceId());

    List<MoveLine> moveLines = new ArrayList<MoveLine>();

    Set<AnalyticAccount> analyticAccounts = new HashSet<AnalyticAccount>();

    int moveLineId = 1;

    if (partner == null) {
      throw new AxelorException(
          invoice,
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get(IExceptionMessage.MOVE_LINE_1),
          invoice.getInvoiceId());
    }
    if (partnerAccount == null) {
      throw new AxelorException(
          invoice,
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get(IExceptionMessage.MOVE_LINE_2),
          invoice.getInvoiceId());
    }

    String origin = invoice.getInvoiceId();

    if (InvoiceToolService.isPurchase(invoice)) {
      origin = invoice.getSupplierInvoiceNb();
    }

    // Creation of partner move line
    MoveLine moveLine1 =
        this.createMoveLine(
            move,
            partner,
            partnerAccount,
            invoice.getInTaxTotal(),
            invoice.getCompanyInTaxTotal(),
            null,
            isDebitCustomer,
            invoice.getInvoiceDate(),
            invoice.getDueDate(),
            invoice.getOriginDate(),
            moveLineId++,
            origin,
            null);
    moveLines.add(moveLine1);

    // Creation of product move lines for each invoice line
    for (InvoiceLine invoiceLine : invoice.getInvoiceLineList()) {

      BigDecimal companyExTaxTotal = invoiceLine.getCompanyExTaxTotal();

      if (companyExTaxTotal.compareTo(BigDecimal.ZERO) != 0) {

        analyticAccounts.clear();

        Account account = invoiceLine.getAccount();

        if (account == null) {
          throw new AxelorException(
              move,
              TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
              I18n.get(IExceptionMessage.MOVE_LINE_4),
              invoiceLine.getName(),
              company.getName());
        }

        companyExTaxTotal = invoiceLine.getCompanyExTaxTotal();

        log.debug(
            "Traitement de la ligne de facture : compte comptable = {}, montant = {}",
            new Object[] {account.getName(), companyExTaxTotal});

        if (invoiceLine.getAnalyticDistributionTemplate() == null
            && (invoiceLine.getAnalyticMoveLineList() == null
                || invoiceLine.getAnalyticMoveLineList().isEmpty())
            && account.getAnalyticDistributionAuthorized()
            && account.getAnalyticDistributionRequiredOnInvoiceLines()) {
          throw new AxelorException(
              move,
              TraceBackRepository.CATEGORY_MISSING_FIELD,
              I18n.get(IExceptionMessage.ANALYTIC_DISTRIBUTION_MISSING),
              invoiceLine.getName(),
              company.getName());
        }

        MoveLine moveLine =
            this.createMoveLine(
                move,
                partner,
                account,
                invoiceLine.getExTaxTotal(),
                companyExTaxTotal,
                null,
                !isDebitCustomer,
                invoice.getInvoiceDate(),
                null,
                invoice.getOriginDate(),
                moveLineId++,
                origin,
                invoiceLine.getProductName());

        moveLine.setAnalyticDistributionTemplate(invoiceLine.getAnalyticDistributionTemplate());
        if (invoiceLine.getAnalyticMoveLineList() != null
            && !invoiceLine.getAnalyticMoveLineList().isEmpty()) {
          for (AnalyticMoveLine invoiceAnalyticMoveLine : invoiceLine.getAnalyticMoveLineList()) {
            AnalyticMoveLine analyticMoveLine =
                analyticMoveLineRepository.copy(invoiceAnalyticMoveLine, false);
            analyticMoveLine.setTypeSelect(AnalyticMoveLineRepository.STATUS_REAL_ACCOUNTING);
            analyticMoveLine.setInvoiceLine(null);
            analyticMoveLine.setAccount(moveLine.getAccount());
            analyticMoveLine.setAccountType(moveLine.getAccount().getAccountType());
            analyticMoveLineService.updateAnalyticMoveLine(
                analyticMoveLine,
                moveLine.getDebit().add(moveLine.getCredit()),
                moveLine.getDate());
            moveLine.addAnalyticMoveLineListItem(analyticMoveLine);
          }
        } else {
          moveLineComputeAnalyticService.generateAnalyticMoveLines(moveLine);
        }

        TaxLine taxLine = invoiceLine.getTaxLine();
        if (taxLine != null) {
          moveLine.setTaxLine(taxLine);
          moveLine.setTaxRate(taxLine.getValue());
          moveLine.setTaxCode(taxLine.getTax().getCode());
        }

        moveLines.add(moveLine);
      }
    }

    // Creation of tax move lines for each invoice line tax
    for (InvoiceLineTax invoiceLineTax : invoice.getInvoiceLineTaxList()) {

      BigDecimal companyTaxTotal = invoiceLineTax.getCompanyTaxTotal();

      if (companyTaxTotal.compareTo(BigDecimal.ZERO) != 0) {

        Tax tax = invoiceLineTax.getTaxLine().getTax();
        boolean hasFixedAssets = !invoiceLineTax.getSubTotalOfFixedAssets().equals(BigDecimal.ZERO);
        boolean hasOtherAssets =
            !invoiceLineTax.getSubTotalExcludingFixedAssets().equals(BigDecimal.ZERO);
        Account account;
        MoveLine moveLine;
        if (hasFixedAssets
            && invoiceLineTax.getCompanySubTotalOfFixedAssets().compareTo(BigDecimal.ZERO) != 0) {
          account = taxAccountService.getAccount(tax, company, isPurchase, true);
          if (account == null) {
            throw new AxelorException(
                move,
                TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
                I18n.get(IExceptionMessage.MOVE_LINE_6),
                tax.getName(),
                company.getName());
          }
          moveLine =
              this.createMoveLine(
                  move,
                  partner,
                  account,
                  invoiceLineTax.getSubTotalOfFixedAssets(),
                  invoiceLineTax.getCompanySubTotalOfFixedAssets(),
                  null,
                  !isDebitCustomer,
                  invoice.getInvoiceDate(),
                  null,
                  invoice.getOriginDate(),
                  moveLineId++,
                  origin,
                  null);

          moveLine.setTaxLine(invoiceLineTax.getTaxLine());
          moveLine.setTaxRate(invoiceLineTax.getTaxLine().getValue());
          moveLine.setTaxCode(tax.getCode());
          moveLines.add(moveLine);
        }

        if (hasOtherAssets
            && invoiceLineTax.getCompanySubTotalExcludingFixedAssets().compareTo(BigDecimal.ZERO)
                != 0) {
          account = taxAccountService.getAccount(tax, company, isPurchase, false);
          if (account == null) {
            throw new AxelorException(
                move,
                TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
                I18n.get(IExceptionMessage.MOVE_LINE_6),
                tax.getName(),
                company.getName());
          }
          moveLine =
              this.createMoveLine(
                  move,
                  partner,
                  account,
                  invoiceLineTax.getSubTotalExcludingFixedAssets(),
                  invoiceLineTax.getCompanySubTotalExcludingFixedAssets(),
                  null,
                  !isDebitCustomer,
                  invoice.getInvoiceDate(),
                  null,
                  invoice.getOriginDate(),
                  moveLineId++,
                  origin,
                  null);
          moveLine.setTaxLine(invoiceLineTax.getTaxLine());
          moveLine.setTaxRate(invoiceLineTax.getTaxLine().getValue());
          moveLine.setTaxCode(tax.getCode());
          moveLines.add(moveLine);
        }
      }
    }

    if (consolidate) {
      moveLineConsolidateService.consolidateMoveLines(moveLines);
    }

    return moveLines;
  }

  @Override
  public MoveLine createMoveLineForAutoTax(
      Move move,
      Map<String, MoveLine> map,
      Map<String, MoveLine> newMap,
      MoveLine moveLine,
      TaxLine taxLine,
      String accountType)
      throws AxelorException {
    BigDecimal debit = moveLine.getDebit();
    BigDecimal credit = moveLine.getCredit();
    LocalDate date = moveLine.getDate();
    Company company = move.getCompany();
    Account newAccount = null;

    if (accountType.equals(AccountTypeRepository.TYPE_DEBT)
        || accountType.equals(AccountTypeRepository.TYPE_CHARGE)) {
      newAccount = taxAccountService.getAccount(taxLine.getTax(), company, true, false);
    } else if (accountType.equals(AccountTypeRepository.TYPE_INCOME)) {
      newAccount = taxAccountService.getAccount(taxLine.getTax(), company, false, false);
    } else if (accountType.equals(AccountTypeRepository.TYPE_ASSET)) {
      newAccount = taxAccountService.getAccount(taxLine.getTax(), company, true, true);
    }

    if (newAccount == null) {
      throw new AxelorException(
          move,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(IExceptionMessage.MOVE_LINE_6),
          taxLine.getName(),
          company.getName());
    }
    if (move.getPartner().getFiscalPosition() != null) {
      newAccount =
          fiscalPositionAccountService.getAccount(
              move.getPartner().getFiscalPosition(), newAccount);
    }
    String newSourceTaxLineKey = newAccount.getCode() + taxLine.getId();
    MoveLine newOrUpdatedMoveLine = new MoveLine();

    newOrUpdatedMoveLine.setAccount(newAccount);
    if (!map.containsKey(newSourceTaxLineKey) && !newMap.containsKey(newSourceTaxLineKey)) {

      newOrUpdatedMoveLine =
          this.createNewMoveLine(debit, credit, date, accountType, taxLine, newOrUpdatedMoveLine);
    } else {

      if (newMap.containsKey(newSourceTaxLineKey)) {
        newOrUpdatedMoveLine = newMap.get(newSourceTaxLineKey);
      } else if (!newMap.containsKey(newSourceTaxLineKey) && map.containsKey(newSourceTaxLineKey)) {
        newOrUpdatedMoveLine = map.get(newSourceTaxLineKey);
      }
      newOrUpdatedMoveLine.setDebit(
          newOrUpdatedMoveLine.getDebit().add(debit.multiply(taxLine.getValue())));
      newOrUpdatedMoveLine.setCredit(
          newOrUpdatedMoveLine.getCredit().add(credit.multiply(taxLine.getValue())));
    }
    newOrUpdatedMoveLine.setMove(move);
    newOrUpdatedMoveLine = moveLineToolService.setCurrencyAmount(newOrUpdatedMoveLine);
    newOrUpdatedMoveLine.setOrigin(move.getOrigin());
    newOrUpdatedMoveLine.setDescription(move.getDescription());
    newOrUpdatedMoveLine.setOriginDate(move.getOriginDate());
    if (newOrUpdatedMoveLine.getDebit().signum() != 0
        || newOrUpdatedMoveLine.getCredit().signum() != 0) {
      newMap.put(newSourceTaxLineKey, newOrUpdatedMoveLine);
    }
    return newOrUpdatedMoveLine;
  }

  protected MoveLine createNewMoveLine(
      BigDecimal debit,
      BigDecimal credit,
      LocalDate date,
      String accountType,
      TaxLine taxLine,
      MoveLine newOrUpdatedMoveLine) {

    newOrUpdatedMoveLine.setSourceTaxLine(taxLine);
    newOrUpdatedMoveLine.setTaxLine(taxLine);
    newOrUpdatedMoveLine.setDebit(debit.multiply(taxLine.getValue()));
    newOrUpdatedMoveLine.setCredit(credit.multiply(taxLine.getValue()));
    newOrUpdatedMoveLine.setDescription(taxLine.getTax().getName());
    newOrUpdatedMoveLine.setDate(date);

    return newOrUpdatedMoveLine;
  }
}
